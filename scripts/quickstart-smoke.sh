#!/usr/bin/env bash
# README steps 5 to 7, with the CLI, against the running stack (scripts/quickstart.sh first):
#   1. run, approve, wait for COMPLETED: one order at the merchant;
#   2. the drop preset (the response to `complete` is lost), run, approve, wait: the intent still completes, it went
#      through RECONCILING, and the merchant holds exactly one new order (2 in all);
#   3. bob asks for alice's intent: not found (T6);
#   4. several merchants: S22 in miniature (intents at two merchants, approve one, reject the other: the orders at A and B are
#      1 and 0) and S20 in miniature (merchant_503_20s at B, which A does not feel: a purchase at A still completes).
# Exits non-zero on the first check that fails. The scripted model is used; no key is needed.
set -euo pipefail
cd "$(dirname "$0")/.."

cli() { scripts/ucplab "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
# The id on the line "  intent <id>" of a run's output.
intent_of() { awk '/^  intent /{print $2}' | tail -1; }

buy() {
  local user=$1 run intent
  run=$(cli --user "$user" run hi | awk '/^run /{print $2}')
  [ -n "$run" ] || fail "no run id"
  intent=$(cli --user "$user" answer "$run" "a blue mug please" | intent_of)
  [ -n "$intent" ] || fail "the run proposed no purchase"
  cli --user "$user" approve "$intent" >/dev/null
  cli --user "$user" wait "$intent" --timeout 90s | grep -q 'COMPLETED' || fail "intent $intent did not complete"
  echo "$intent"
}

orders() { cli --user alice lab orders | awk '{print $2}'; }

echo "== reset the lab"
cli --user alice lab reset >/dev/null
[ "$(orders)" = 0 ] || fail "the lab was not empty after a reset"

echo "== 1. run, approve, wait: COMPLETED, one order"
first=$(buy alice)
[ "$(orders)" = 1 ] || fail "expected 1 order, saw $(orders)"
echo "ok: $first completed, orders = 1"

echo "== 2. the drop preset, run, approve, wait: COMPLETED, one new order"
cli --user alice lab faults drop_next_complete >/dev/null
second=$(buy alice)
cli --user alice events "$second" | grep -q 'reconcile_read' || fail "intent $second never reconciled: the drop did not take effect"
[ "$(orders)" = 2 ] || fail "expected 2 orders after the drop, saw $(orders)"
echo "ok: $second completed after a lost response, orders = 2"

echo "== 3. bob and alice's intent: not found"
if out=$(cli --user bob intent "$first" 2>&1); then fail "bob could read alice's intent: $out"; fi
echo "$out" | grep -q 'not found' || fail "bob's answer was not 'not found': $out"
echo "ok: not found for bob"

# An intent awaiting approval, not yet decided. $2 is "mug" (the scripted model's first hit is merchant-a's Blue Mug) or
# "cap" (only merchant-b's catalog, dev/catalogs/b.json, has a Wool Cap).
propose() {
  local user=$1 what=$2 run out intent
  if [ "$what" = mug ]; then
    run=$(cli --user "$user" run hi | awk '/^run /{print $2}')
    out=$(cli --user "$user" answer "$run" "a blue mug please")
  else
    out=$(cli --user "$user" run "wool cap")
  fi
  intent=$(echo "$out" | intent_of)
  # The CLI's own lines say how the run ended (its status and reason); the agent's trace in the stack's logs says which
  # tool failed and why.
  [ -n "$intent" ] || fail "the run for '$what' proposed no purchase; the CLI said: $out"
  echo "$intent"
}

echo "== 4. several merchants: S22 and S20 in miniature"
cli --user alice lab reset >/dev/null
[ "$(cli --user alice lab orders --merchant merchant-a | awk '{print $2}')" = 0 ] || fail "merchant-a was not empty after a reset"
atA=$(propose alice mug)
atB=$(propose alice cap)
list=$(cli --user alice intents --state AWAITING_APPROVAL)
echo "$list" | grep "$atA" | grep -q 'merchant-a' || fail "intent $atA is not at merchant-a: $list"
echo "$list" | grep "$atB" | grep -q 'merchant-b' || fail "intent $atB is not at merchant-b (is dev/catalogs/b.json mounted?): $list"
cli --user alice approval "$atB" | grep -q 'merchant merchant-b' || fail "the approval card of $atB does not name merchant-b"
cli --user alice approve "$atA" >/dev/null
cli --user alice wait "$atA" --timeout 90s | grep -q 'COMPLETED' || fail "intent $atA did not complete"
cli --user alice reject "$atB" >/dev/null
cli --user alice intent "$atB" | grep -q 'REJECTED' || fail "intent $atB was not rejected"
ordersA=$(cli --user alice lab orders --merchant merchant-a | awk '{print $2}')
ordersB=$(cli --user alice lab orders --merchant merchant-b | awk '{print $2}')
[ "$ordersA" = 1 ] && [ "$ordersB" = 0 ] || fail "expected orders 1 at merchant-a and 0 at merchant-b, saw $ordersA and $ordersB"
echo "ok: $atA approved at merchant-a, $atB rejected at merchant-b, orders = $ordersA and $ordersB"

cli --user alice lab faults merchant_503_20s --merchant merchant-b >/dev/null
statusB=$(docker compose exec -T fault-proxy-b curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9102/.well-known/ucp)
statusA=$(docker compose exec -T fault-proxy-a curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9101/.well-known/ucp)
[ "$statusB" = 503 ] || fail "merchant_503_20s did not take effect at merchant-b (its proxy answered $statusB)"
[ "$statusA" = 200 ] || fail "merchant-a's proxy answered $statusA while only merchant-b has a fault"
third=$(buy alice)
[ "$(orders)" = 2 ] || fail "expected 2 orders at merchant-a after the purchase during B's 503, saw $(orders)"
cli --user alice lab faults clear --merchant merchant-b >/dev/null
statusB=$(docker compose exec -T fault-proxy-b curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9102/.well-known/ucp)
[ "$statusB" = 200 ] || fail "merchant-b's proxy still answers $statusB after the faults were cleared"
echo "ok: $third completed at merchant-a while merchant-b answered 503; cleared, merchant-b answers again"
echo "SMOKE PASSED"
