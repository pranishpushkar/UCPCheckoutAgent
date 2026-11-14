#!/usr/bin/env bash
# README steps 5 to 7, with the CLI, against the running stack (scripts/quickstart.sh first):
#   1. run, approve, wait for COMPLETED: one order at the merchant;
#   2. the drop preset (the response to `complete` is lost), run, approve, wait: the intent still completes, it went
#      through RECONCILING, and the merchant holds exactly one new order (2 in all);
#   3. bob asks for alice's intent: not found (T6).
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
echo "SMOKE PASSED"
