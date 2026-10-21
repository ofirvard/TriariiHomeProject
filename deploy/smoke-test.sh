#!/usr/bin/env bash
# Smoke test for a deployed stack, through nginx. Exits non-zero on the first failure.
#   deploy/smoke-test.sh [base_url]     (default http://localhost)
set -euo pipefail

BASE="${1:-http://localhost}"
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok:   $*"; }

# The stack may still be starting: wait up to 90 s for both services to report UP.
for path in /health /alert-service/health; do
    for _ in $(seq 1 45); do
        curl -fsS -m 3 "$BASE$path" 2>/dev/null | grep -q '"status":"UP"' && break
        sleep 2
    done
    curl -fsS -m 3 "$BASE$path" | grep -q '"status":"UP"' || fail "$path is not UP"
    ok "$path is UP (database and broker reachable)"
done

curl -fsS -m 5 "$BASE/api/v1/rules" | grep -q '"rule_id"' || fail "no rules returned"
ok "rules are loaded"

sensor="smoke-$(date +%s)"
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
response=$(curl -fsS -m 5 -H 'Content-Type: application/json' "$BASE/api/v1/observations" -d \
    "{\"sensor_id\":\"$sensor\",\"timestamp\":\"$now\",\"frequency_mhz\":915.0,\"bandwidth_khz\":125,\"power_dbm\":-90,\"location\":{\"lat\":32.08,\"lon\":34.78}}") \
    || fail "POST /api/v1/observations failed"
grep -q '"accepted":1' <<<"$response" || fail "observation not accepted: $response"
ok "observation accepted"

curl -fsS -m 5 "$BASE/api/v1/observations?sensor_id=$sensor" | grep -q "\"sensor_id\":\"$sensor\"" \
    || fail "stored observation not found"
ok "observation stored and queryable"

curl -fsS -m 5 -o /dev/null "$BASE/api/v1/alerts" || fail "GET /api/v1/alerts failed"
ok "alerts endpoint responds"

echo "smoke test passed for $BASE"
