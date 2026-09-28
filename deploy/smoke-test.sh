#!/usr/bin/env bash
# First run of the real stack, from docker-compose.yml: the Actual image and the bridge
# image, set up the way the app does it over HTTP. CI runs this before publishing the
# bridge image; you can run it too (it uses a throwaway directory).
#
#   BRIDGE_IMAGE=centsible-bridge:dev ./deploy/smoke-test.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
cp "$here/docker-compose.yml" "$here/docker-compose.smoke.yml" "$work/"
cat > "$work/.env" <<ENV
BRIDGE_PUBLIC_URL=http://127.0.0.1:8787
CLOUDFLARE_TUNNEL_TOKEN=unused
ENV
cd "$work"
compose() { docker compose -p centsible-smoke -f docker-compose.yml -f docker-compose.smoke.yml "$@"; }
cleanup() { compose logs bridge > "$work/bridge.log" 2>&1 || true; compose down -v > /dev/null 2>&1 || true; }
trap cleanup EXIT
api() { curl -sS -m 30 "$@"; }
wait_for() { for _ in $(seq 1 90); do if "$@" > /dev/null 2>&1; then return 0; fi; sleep 2; done; echo "timed out: $*" >&2; return 1; }
fail() { echo "FAIL: $*" >&2; compose logs --tail 50 >&2 || true; exit 1; }

compose up -d actual bridge
wait_for curl -sf http://127.0.0.1:8787/v1/health

code="$(compose logs bridge | grep -o 'Setup code: [A-Z0-9-]*' | head -1 | cut -d' ' -f3)"
[ -n "$code" ] || fail "no setup code in the bridge logs"
echo "setup code: $code"

# Actual may still be migrating; wait until the bridge sees a new server.
wait_for sh -c "curl -sf http://127.0.0.1:8787/v1/setup | grep -q needs-password" || fail "setup status: $(api http://127.0.0.1:8787/v1/setup)"

claim="$(api -X POST http://127.0.0.1:8787/v1/setup/claim -H 'content-type: application/json' \
  -d "{\"setupCode\":\"$code\",\"displayName\":\"Jo\",\"deviceName\":\"CI\",\"platform\":\"android\",\"actualPassword\":\"smoke-test-pass\"}")"
token="$(echo "$claim" | jq -r '.accessToken // empty')"
[ -n "$token" ] || fail "claim: $claim"
echo "claimed: $(echo "$claim" | jq -c .member)"

created="$(api -X POST http://127.0.0.1:8787/v1/budgets -H "authorization: Bearer $token" -H 'content-type: application/json' -d '{"name":"Household"}')"
[ "$(echo "$created" | jq -r .name)" = "Household" ] || fail "create budget: $created"

# A restart must sign back in to Actual with the password kept from setup.
compose restart bridge
wait_for curl -sf http://127.0.0.1:8787/v1/health
budgets="$(api http://127.0.0.1:8787/v1/budgets -H "authorization: Bearer $token")"
[ "$(echo "$budgets" | jq -r '.items[0].name')" = "Household" ] || fail "after restart: $budgets"
[ "$(api http://127.0.0.1:8787/v1/setup | jq -r .needsOwner)" = "false" ] || fail "still unclaimed after restart"
# Backups inside the real container: every budget as Actual's .zip, plus the household.
backup="$(api -X POST http://127.0.0.1:8787/v1/server/backups -H "authorization: Bearer $token")"
[ "$(echo "$backup" | jq -r '.budgets[0].name')" = "Household" ] || fail "backup: $backup"
[ "$(echo "$backup" | jq -r .household)" = "true" ] || fail "backup without household: $backup"
echo "backup: $(echo "$backup" | jq -c '{id, size}')"
echo "smoke test passed"
