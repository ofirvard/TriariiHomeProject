#!/usr/bin/env bash
# RF sensor network simulator: sends one observation batch per tick to ingest-service.
# Needs only bash, curl and awk.
#
# Timeline (seconds since start):
#   whole run   5 sensors send background traffic                     -> no alert
#               (433/868/915 MHz, 5 GHz, and weak 2.4 GHz below -50 dBm)
#   10..70      strong emitter at 2437 MHz, seen by sensor-03 and -04  -> power_threshold alert opens after 10 s
#   20..23      short burst at 2462 MHz on sensor-01                   -> no alert (shorter than 10 s)
#   after 70    the emitter disappears                                -> alert auto-resolves ~70 s later
set -euo pipefail

URL="http://localhost:8081"
RATE=1          # observations per second, per sensor
DURATION=150    # seconds

usage() { echo "Usage: $0 [-u ingest_url (default $URL)] [-r obs/s/sensor (default $RATE)] [-d seconds (default $DURATION)]"; }
while getopts "u:r:d:h" opt; do
    case "$opt" in
        u) URL="$OPTARG" ;;
        r) RATE="$OPTARG" ;;
        d) DURATION="$OPTARG" ;;
        h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
awk -v r="$RATE" 'BEGIN { exit !(r + 0 > 0) }' || { echo "rate must be a positive number" >&2; exit 2; }
[[ "$DURATION" =~ ^[0-9]+$ ]] || { echo "duration must be whole seconds" >&2; exit 2; }
INTERVAL=$(awk -v r="$RATE" 'BEGIN { printf "%.3f", 1 / r }')

SENSORS=(sensor-01 sensor-02 sensor-03 sensor-04 sensor-05)
LATS=(32.0853 32.0700 32.1093 32.0631 32.0950)
LONS=(34.7818 34.7900 34.8065 34.7700 34.8300)
# "frequency_mhz bandwidth_khz min_dbm max_dbm"; the 2.4 GHz channels stay below the -50 dBm threshold
BACKGROUND=("433.92 200 -98 -72" "868.30 125 -100 -75" "915.00 500 -97 -70"
            "2412.00 20000 -92 -62" "2462.00 20000 -92 -62" "5180.00 20000 -95 -68")

# Random power in [min, max] dBm with one decimal
dbm() { awk -v min="$1" -v max="$2" -v seed="$RANDOM" 'BEGIN { srand(seed); printf "%.1f", min + rand() * (max - min) }'; }

# obs <sensor index> <timestamp> <frequency_mhz> <bandwidth_khz> <power_dbm>
obs() {
    printf '{"sensor_id":"%s","timestamp":"%s","frequency_mhz":%s,"bandwidth_khz":%s,"power_dbm":%s,"location":{"lat":%s,"lon":%s}}' \
        "${SENSORS[$1]}" "$2" "$3" "$4" "$5" "${LATS[$1]}" "${LONS[$1]}"
}

echo "sending to $URL for ${DURATION}s at $RATE obs/s/sensor"
START=$(date +%s)
while (( (t = $(date +%s) - START) < DURATION )); do
    ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    items=()
    for i in "${!SENSORS[@]}"; do
        read -r freq bw min max <<<"${BACKGROUND[RANDOM % ${#BACKGROUND[@]}]}"
        items+=("$(obs "$i" "$ts" "$freq" "$bw" "$(dbm "$min" "$max")")")
    done
    phase="background"
    if (( t >= 10 && t < 70 )); then
        phase="emitter on"
        items+=("$(obs 2 "$ts" 2437.0 20000 "$(dbm -42 -38)")" "$(obs 3 "$ts" 2437.0 20000 "$(dbm -48 -44)")")
    elif (( t >= 70 )); then
        phase="emitter gone"
    fi
    if (( t >= 20 && t < 23 )); then
        phase="$phase + burst"
        items+=("$(obs 0 "$ts" 2462.0 20000 "$(dbm -36 -33)")")
    fi

    body="[$(IFS=,; echo "${items[*]}")]"
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 -H 'Content-Type: application/json' \
        --data-binary "$body" "$URL/api/v1/observations" || true)
    printf '[t+%03ds] %-22s sent %d -> HTTP %s\n' "$t" "$phase" "${#items[@]}" "$code"
    sleep "$INTERVAL"
done
