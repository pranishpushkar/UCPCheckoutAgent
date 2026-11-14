#!/usr/bin/env bash
# Runs the official UCP conformance suite against the lab merchant (spike, docs/conformance.md).
#
# Usage: scripts/run-conformance.sh [merchant-base-url]
#   With no argument it builds and starts the merchant on a free port (the same classes as scripts/run-merchant.sh),
#   runs the suite, and stops it. With a URL (for example one started by scripts/run-merchant.sh) it only runs the suite.
# Needs: uv, git, curl, python3, a JDK. The suite and its Python SDK are cloned into .conformance/ (gitignored) at the
# versions pinned below, the same pair the suite's own CI uses. Results: .conformance/results/<run>.txt.
# The merchant is not changed to make the suite pass (M2): the point is to record where it differs.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"
WORK="$REPO/.conformance"
CONFORMANCE_SHA=0642adfb7930a805bd56f785c6bc61704d6b28a4   # same pin as docs/interop/pin.txt; the M2 record used 1201fb78 (see docs/conformance.md)
SDK_TAG=v2026-04-08-6                                       # what the suite's CI checks out beside it
SECRET="${SIMULATION_SECRET:-lab-simulation-secret}"        # the merchant has no simulation endpoint, see the docs

command -v uv >/dev/null || { echo "uv is not installed (https://docs.astral.sh/uv/)" >&2; exit 1; }
mkdir -p "$WORK/results"

fetch() { # dir url ref
  if [ ! -d "$1/.git" ]; then git clone -q "$2" "$1"; fi
  git -C "$1" fetch -q --tags origin
  git -C "$1" checkout -q --detach "$3"
}
fetch "$WORK/conformance" https://github.com/Universal-Commerce-Protocol/conformance.git "$CONFORMANCE_SHA"
fetch "$WORK/python-sdk" https://github.com/Universal-Commerce-Protocol/python-sdk.git "$SDK_TAG"
uv sync -q --directory "$WORK/conformance"

PID=""
cleanup() { [ -n "$PID" ] && kill "$PID" 2>/dev/null || true; }
trap cleanup EXIT
if [ $# -ge 1 ]; then
  URL="${1%/}"
else
  ./mvnw -B -q -ntp -pl merchant -am package -DskipTests
  PORT="$(python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')"
  java -cp merchant/target/classes dev.ucplab.merchant.MerchantServer "$PORT" >"$WORK/results/merchant.log" 2>&1 &
  PID=$!
  URL="http://127.0.0.1:$PORT"
  for _ in $(seq 1 50); do curl -s -o /dev/null "$URL/.well-known/ucp" && break; sleep 0.1; done
fi
curl -s -o /dev/null -X POST "$URL/_lab/reset" -d '{}'
# Sold out, so the suite's out-of-stock tests have an item to use (the merchant's answer is 200, the suite expects 409).
curl -s -o /dev/null -X POST "$URL/_lab/stock" -d '{"item_id":"item_789","stock":0}'

# The merchant's own values. It declares only the checkout capability; the suite's sample input also requires order,
# discount, fulfillment and buyer_consent, which it does not have.
cat >"$WORK/conformance_input.json" <<'JSON'
{
  "ucp_version": "2026-08-25",
  "required_capabilities": ["dev.ucp.shopping.checkout"],
  "currency": "USD",
  "items": [{"id": "item_123", "title": "Red T-Shirt", "price": 2500}],
  "out_of_stock_item": {"id": "item_789", "title": "Limited Sneakers"},
  "non_existent_item": {"id": "no_such_item", "title": "Non-existent Item"}
}
JSON
cat >"$WORK/test_fixtures.json" <<'JSON'
{"test_fixtures": {"valid_item": {"sku": "item_123", "expected_price": 25.0, "quantity": 1}}}
JSON

RUN="$WORK/results/run-$(date +%Y%m%d-%H%M%S).txt"
echo "merchant $URL, suite $CONFORMANCE_SHA, sdk $SDK_TAG; output $RUN"
set +e
uv run --directory "$WORK/conformance" ucp-conformance \
  --server_url="$URL" --simulation_secret="$SECRET" \
  --conformance_input="$WORK/conformance_input.json" --fixture_config="$WORK/test_fixtures.json" \
  --suite=all -v 2>&1 | tee "$RUN"
STATUS=${PIPESTATUS[0]}
echo "suite exit status $STATUS (non-zero is expected while the gaps in docs/conformance.md exist)"
