#!/usr/bin/env bash
# RF sensor network simulator: sends realistic observation batches to ingest-service and shows the
# resulting alerts. Needs only bash, curl and awk.
#
# Timeline (seconds since start; every boundary can be overridden by env var):
#   whole run     background traffic from 5 sensors            -> must NOT alert
#                 (ISM 433/868/915 MHz, 5 GHz, and weak 2.4 GHz Wi-Fi below the -50 dBm threshold)
#   10 .. 70      strong persistent emitter at 2437 MHz,        -> power_threshold alert opens after 10 s
#                 seen by sensor-03 and sensor-04
#   20 .. 23      short burst at 2462 MHz on sensor-01          -> must NOT alert (shorter than 10 s)
#   after 70      the emitter disappears                       -> alert auto-resolves ~70 s later
#                                                                 (resolve_after_s 60 + 10 s grace)
set -euo pipefail

INGEST_URL="${INGEST_URL:-http://localhost:8081}"
ALERTS_URL="${ALERTS_URL:-http://localhost:8082}"
RATE="${RATE:-1}"                  # observations per second, per sensor
DURATION="${DURATION:-150}"        # seconds
SCENARIO="${SCENARIO:-all}"        # all | background | emitter | burst
EMITTER_START="${EMITTER_START:-10}"
EMITTER_STOP="${EMITTER_STOP:-70}"
BURST_START="${BURST_START:-20}"
BURST_LENGTH="${BURST_LENGTH:-3}"

usage() {
    cat <<EOF
Usage: $(basename "$0") [-u ingest_url] [-a alerts_url] [-r rate] [-d seconds] [-s scenario]

  -u  ingest-service base URL      (env INGEST_URL, default $INGEST_URL)
  -a  alert-service base URL       (env ALERTS_URL, default $ALERTS_URL; "" to skip alert status)
  -r  observations/second/sensor   (env RATE, default $RATE; fractions allowed, e.g. 0.5)
  -d  run time in seconds          (env DURATION, default $DURATION)
  -s  scenario                     (env SCENARIO, default $SCENARIO)
        all         background + persistent emitter that disappears + short burst
        background  background traffic only (no alert expected)
        emitter     background + persistent emitter that disappears (alert opens, then auto-resolves)
        burst       background + short burst (no alert expected)

Phase boundaries: EMITTER_START=$EMITTER_START EMITTER_STOP=$EMITTER_STOP BURST_START=$BURST_START BURST_LENGTH=$BURST_LENGTH
Behind the reverse proxy both URLs are the same, e.g. -u http://<vm> -a http://<vm>
EOF
}

while getopts "u:a:r:d:s:h" opt; do
    case "$opt" in
        u) INGEST_URL="$OPTARG" ;;
        a) ALERTS_URL="$OPTARG" ;;
        r) RATE="$OPTARG" ;;
        d) DURATION="$OPTARG" ;;
        s) SCENARIO="$OPTARG" ;;
        h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done

die() { echo "error: $*" >&2; exit 2; }
awk -v r="$RATE" 'BEGIN { exit !(r + 0 > 0) }' || die "rate must be a positive number, got '$RATE'"
[[ "$DURATION" =~ ^[0-9]+$ ]] || die "duration must be whole seconds, got '$DURATION'"
case "$SCENARIO" in all|background|emitter|burst) ;; *) die "unknown scenario '$SCENARIO'" ;; esac
command -v curl >/dev/null || die "curl is required"

INTERVAL=$(awk -v r="$RATE" 'BEGIN { printf "%.3f", 1 / r }')
EMITTER=false; BURST=false
[[ "$SCENARIO" == all || "$SCENARIO" == emitter ]] && EMITTER=true
[[ "$SCENARIO" == all || "$SCENARIO" == burst ]] && BURST=true

# Sensors spread around Tel Aviv.
SENSORS=(sensor-01 sensor-02 sensor-03 sensor-04 sensor-05)
LATS=(32.0853 32.0700 32.1093 32.0631 32.0950)
LONS=(34.7818 34.7900 34.8065 34.7700 34.8300)

# Background channels: "frequency_mhz bandwidth_khz min_dbm max_dbm". The 2.4 GHz entries are inside the
# protected ISM band but always below the -50 dBm threshold, so they exercise the threshold too.
BACKGROUND=(
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
    if ! body=$(curl -sS -m 3 "$ALERTS_URL/api/v1/alerts?min_mhz=2400&max_mhz=2483.5&limit=3" 2>/dev/null); then
        echo "            alerts: alert-service unreachable"
        return
    fi
    if [[ "$body" == "[]" ]]; then
        echo "            alerts (2.4 GHz ISM): none"
        return
    fi
    echo "            alerts (2.4 GHz ISM, newest first):"
    sed 's/},{/}\n{/g' <<<"$body" | sed -E \
        's/.*"alert_id":"([^"]*)".*"state":"([A-Z]+)".*"sensor_ids":\[([^]]*)\].*"occurrences":([0-9]+).*/              \1  \2  occurrences=\4  sensors=[\3]/' \
        | tr -d '"'
}

SENT=0; FAILED=0; REJECTED=0
summary() {
    echo
    echo "done: $SENT observations sent, $REJECTED rejected by the service, $FAILED failed requests"
    alert_status
}
trap 'summary; exit 0' INT TERM

echo "simulating $SCENARIO for ${DURATION}s at $RATE obs/s/sensor -> $INGEST_URL"
$EMITTER && echo "  emitter at 2437 MHz from ${EMITTER_START}s to ${EMITTER_STOP}s (alert expected, then auto-resolve)"
$BURST && echo "  burst at 2462 MHz from ${BURST_START}s for ${BURST_LENGTH}s (no alert expected)"

START=$(date +%s)
LAST_STATUS=-1
PHASE=""

while :; do
    t=$(( $(date +%s) - START ))
    (( t >= DURATION )) && break
    ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    items=()

    for i in "${!SENSORS[@]}"; do
        read -r freq bw min max <<<"${BACKGROUND[RANDOM % ${#BACKGROUND[@]}]}"
        items+=("$(observation "$i" "$ts" "$freq" "$bw" "$(rand_dbm "$min" "$max")")")
    done

    phase="background"
    if $EMITTER && (( t >= EMITTER_START && t < EMITTER_STOP )); then
        phase="emitter ON"
        items+=("$(observation 2 "$ts" 2437.0 20000 "$(rand_dbm -42 -38)")")   # close to the emitter
        items+=("$(observation 3 "$ts" 2437.0 20000 "$(rand_dbm -48 -44)")")   # further away
    elif $EMITTER && (( t >= EMITTER_STOP )); then
        phase="emitter gone"
    fi
    if $BURST && (( t >= BURST_START && t < BURST_START + BURST_LENGTH )); then
        phase="$phase + burst"
        items+=("$(observation 0 "$ts" 2462.0 20000 "$(rand_dbm -36 -33)")")
    fi
    if [[ "$phase" != "$PHASE" ]]; then
        printf '[t+%03ds] phase: %s\n' "$t" "$phase"
        PHASE="$phase"
    fi

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
        printf '[t+%03ds] request failed: %s\n' "$t" "$response"
    fi

    if (( t % 10 == 0 && t != LAST_STATUS )); then
        LAST_STATUS=$t
        printf '[t+%03ds] sent %d observations so far\n' "$t" "$SENT"
        alert_status
    fi
    sleep "$INTERVAL"
done

summary
