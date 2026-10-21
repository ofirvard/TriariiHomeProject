#!/usr/bin/env bash
# RF sensor network simulator: sends realistic observation batches to ingest-service and shows the
# resulting alerts. Needs only bash, curl and awk.
#
# Scenarios (pick any with -s, default all four). Times are seconds since start; boundaries can be
# overridden by env var:
#   background    5 sensors on ISM 433/868/915 MHz, 5 GHz and weak    -> must NOT alert
#                 2.4 GHz Wi-Fi below the -50 dBm threshold
#   persistent    strong emitter at 2437 MHz on sensor-03,            -> power_threshold alert opens after
#                 PERSISTENT_START .. end of run                         10 s and stays open
#   burst         strong burst at 2462 MHz on sensor-01,               -> must NOT alert (shorter than 10 s)
#                 BURST_START for BURST_LENGTH s
#   disappearing  strong emitter at 2452 MHz on sensor-04,            -> power_threshold alert opens, then
#                 DISAPPEAR_START .. DISAPPEAR_STOP                      auto-resolves ~70 s after it stops
#                                                                        (resolve_after_s 60 + 10 s grace)
set -euo pipefail

ALL_SCENARIOS="background,persistent,burst,disappearing"

INGEST_URL="${INGEST_URL:-http://localhost:8081}"
ALERTS_URL="${ALERTS_URL:-http://localhost:8082}"
RATE="${RATE:-1}"                  # observations per second, per sensor
DURATION="${DURATION:-180}"        # seconds; long enough to see the disappearing emitter's alert resolve
SCENARIOS="${SCENARIOS:-$ALL_SCENARIOS}"
PERSISTENT_START="${PERSISTENT_START:-10}"
BURST_START="${BURST_START:-20}"
BURST_LENGTH="${BURST_LENGTH:-3}"
DISAPPEAR_START="${DISAPPEAR_START:-10}"
DISAPPEAR_STOP="${DISAPPEAR_STOP:-70}"

usage() {
    cat <<EOF
Usage: $(basename "$0") [-u ingest_url] [-a alerts_url] [-r rate] [-d seconds] [-s scenarios]

  -u  ingest-service base URL      (env INGEST_URL, default $INGEST_URL)
  -a  alert-service base URL       (env ALERTS_URL, default $ALERTS_URL; "" to skip alert status)
  -r  observations/second/sensor   (env RATE, default $RATE; fractions allowed, e.g. 0.5)
  -d  run time in seconds          (env DURATION, default $DURATION)
  -s  comma-separated scenarios    (env SCENARIOS, default all)
        background    several sensors producing traffic that triggers no rule
        persistent    strong, persistent emitter in the protected 2.4 GHz band (alert opens)
        burst         short burst in the same band (no alert)
        disappearing  emitter that stops transmitting (alert opens, then auto-resolves)

Phase boundaries: PERSISTENT_START=$PERSISTENT_START BURST_START=$BURST_START BURST_LENGTH=$BURST_LENGTH
                  DISAPPEAR_START=$DISAPPEAR_START DISAPPEAR_STOP=$DISAPPEAR_STOP
Behind the reverse proxy both URLs are the same, e.g. -u http://<vm> -a http://<vm>
EOF
}

while getopts "u:a:r:d:s:h" opt; do
    case "$opt" in
        u) INGEST_URL="$OPTARG" ;;
        a) ALERTS_URL="$OPTARG" ;;
        r) RATE="$OPTARG" ;;
        d) DURATION="$OPTARG" ;;
        s) SCENARIOS="$OPTARG" ;;
        h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done

die() { echo "error: $*" >&2; exit 2; }
awk -v r="$RATE" 'BEGIN { exit !(r + 0 > 0) }' || die "rate must be a positive number, got '$RATE'"
[[ "$DURATION" =~ ^[0-9]+$ ]] || die "duration must be whole seconds, got '$DURATION'"
command -v curl >/dev/null || die "curl is required"

BACKGROUND=false; PERSISTENT=false; BURST=false; DISAPPEAR=false
IFS=, read -ra selected <<<"$SCENARIOS"
(( ${#selected[@]} > 0 )) || die "no scenario selected"
for s in "${selected[@]}"; do
    case "$s" in
        background)   BACKGROUND=true ;;
        persistent)   PERSISTENT=true ;;
        burst)        BURST=true ;;
        disappearing) DISAPPEAR=true ;;
        *) die "unknown scenario '$s' (choose from $ALL_SCENARIOS)" ;;
    esac
done

INTERVAL=$(awk -v r="$RATE" 'BEGIN { printf "%.3f", 1 / r }')

# Sensors spread around Tel Aviv.
SENSORS=(sensor-01 sensor-02 sensor-03 sensor-04 sensor-05)
LATS=(32.0853 32.0700 32.1093 32.0631 32.0950)
LONS=(34.7818 34.7900 34.8065 34.7700 34.8300)

# Background channels: "frequency_mhz bandwidth_khz min_dbm max_dbm". The 2.4 GHz entries are inside the
# protected ISM band but always below the -50 dBm threshold, so they exercise the threshold too.
CHANNELS=(
    "433.92 200 -98 -72"
    "868.30 125 -100 -75"
    "915.00 500 -97 -70"
    "2412.00 20000 -92 -62"
    "2462.00 20000 -92 -62"
    "5180.00 20000 -95 -68"
)

# Random value in [min, max] with one decimal, e.g. -71.4
rand_dbm() {
    local min=$1 max=$2 v sign=""
    v=$(( min * 10 + RANDOM % ((max - min) * 10 + 1) ))
    (( v < 0 )) && { sign="-"; v=$(( -v )); }
    printf '%s%d.%d' "$sign" $(( v / 10 )) $(( v % 10 ))
}

# observation <sensor index> <timestamp> <frequency_mhz> <bandwidth_khz> <power_dbm>
observation() {
    printf '{"sensor_id":"%s","timestamp":"%s","frequency_mhz":%s,"bandwidth_khz":%s,"power_dbm":%s,"location":{"lat":%s,"lon":%s}}' \
        "${SENSORS[$1]}" "$2" "$3" "$4" "$5" "${LATS[$1]}" "${LONS[$1]}"
}

alert_status() {
    [[ -z "$ALERTS_URL" ]] && return
    local body
    if ! body=$(curl -sS -m 3 "$ALERTS_URL/api/v1/alerts?limit=5" 2>/dev/null); then
        echo "            alerts: alert-service unreachable"
        return
    fi
    if [[ "$body" == "[]" ]]; then
        echo "            alerts: none"
        return
    fi
    echo "            alerts (newest first):"
    printf '%s\n' "${body//'},{'/$'}\n{'}" | sed -E \
        's/.*"alert_id":"([^"]*)".*"rule_id":"([^"]*)".*"state":"([A-Z]+)".*"frequency_mhz":([0-9.]+).*"sensor_ids":\[([^]]*)\].*"occurrences":([0-9]+).*/              \1  \3  \2  \4 MHz  occurrences=\6  sensors=[\5]/' \
        | tr -d '"'
}

SENT=0; FAILED=0; REJECTED=0
summary() {
    echo
    echo "done: $SENT observations sent, $REJECTED rejected by the service, $FAILED failed requests"
    alert_status
}
trap 'summary; exit 0' INT TERM

echo "simulating for ${DURATION}s at $RATE obs/s/sensor -> $INGEST_URL"
$BACKGROUND && echo "  background: 5 sensors, whole run (no alert expected)"
$PERSISTENT && echo "  persistent: 2437 MHz from ${PERSISTENT_START}s to the end (alert opens and stays open)"
$BURST && echo "  burst: 2462 MHz from ${BURST_START}s for ${BURST_LENGTH}s (no alert expected)"
$DISAPPEAR && echo "  disappearing: 2452 MHz from ${DISAPPEAR_START}s to ${DISAPPEAR_STOP}s (alert opens, then auto-resolves)"

START=$(date +%s)
LAST_STATUS=-1
PHASE=""

while :; do
    t=$(( $(date +%s) - START ))
    (( t >= DURATION )) && break
    ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    items=()
    active=()

    if $BACKGROUND; then
        active+=(background)
        for i in "${!SENSORS[@]}"; do
            read -r freq bw min max <<<"${CHANNELS[RANDOM % ${#CHANNELS[@]}]}"
            items+=("$(observation "$i" "$ts" "$freq" "$bw" "$(rand_dbm "$min" "$max")")")
        done
    fi
    if $PERSISTENT && (( t >= PERSISTENT_START )); then
        active+=(persistent)
        items+=("$(observation 2 "$ts" 2437.0 20000 "$(rand_dbm -42 -38)")")
    fi
    if $BURST && (( t >= BURST_START && t < BURST_START + BURST_LENGTH )); then
        active+=(burst)
        items+=("$(observation 0 "$ts" 2462.0 20000 "$(rand_dbm -36 -33)")")
    fi
    if $DISAPPEAR && (( t >= DISAPPEAR_START && t < DISAPPEAR_STOP )); then
        active+=(disappearing)
        items+=("$(observation 3 "$ts" 2452.0 20000 "$(rand_dbm -45 -41)")")
    elif $DISAPPEAR && (( t >= DISAPPEAR_STOP )); then
        active+=("emitter gone")
    fi

    phase=$(IFS=,; echo "${active[*]:-idle}")
    phase="${phase//,/ + }"
    if [[ "$phase" != "$PHASE" ]]; then
        printf '[t+%03ds] phase: %s\n' "$t" "$phase"
        PHASE="$phase"
    fi

    if (( ${#items[@]} > 0 )); then
        body="[$(IFS=,; echo "${items[*]}")]"
        if response=$(curl -sS -m 5 -H 'Content-Type: application/json' --data-binary "$body" \
                -w $'\n%{http_code}' "$INGEST_URL/api/v1/observations" 2>&1); then
            code="${response##*$'\n'}"
            rejected=$(grep -o '"rejected":[0-9]*' <<<"$response" | cut -d: -f2 || true)
            SENT=$(( SENT + ${#items[@]} ))
            REJECTED=$(( REJECTED + ${rejected:-0} ))
            [[ "$code" == 200 ]] || printf '[t+%03ds] HTTP %s: %s\n' "$t" "$code" "${response%$'\n'*}"
        else
            FAILED=$(( FAILED + 1 ))
            printf '[t+%03ds] request failed: %s\n' "$t" "${response%$'\n'*}"
        fi
    fi

    if (( t % 10 == 0 && t != LAST_STATUS )); then
        LAST_STATUS=$t
        printf '[t+%03ds] sent %d observations so far\n' "$t" "$SENT"
        alert_status
    fi
    sleep "$INTERVAL"
done

summary
