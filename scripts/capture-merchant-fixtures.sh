#!/usr/bin/env bash
# Regenerates agent-ucp/src/test/resources/merchant/*.json from the reference merchant's real responses, so the
# CheckoutParser tests stay built from what the merchant actually sends. Run it after the merchant changes, then review
# the git diff: a changed fixture is a changed merchant response.
#
# Usage: scripts/capture-merchant-fixtures.sh [port]   (default: a free port)
# The merchant must be built; this builds it like scripts/run-merchant.sh. Needs curl and python3.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"
./mvnw -B -q -ntp -pl merchant -am package -DskipTests
PORT="${1:-$(python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')}"
OUT="$REPO/agent-ucp/src/test/resources/merchant"
mkdir -p "$OUT"
B="http://127.0.0.1:$PORT"
java -cp "$REPO/merchant/target/classes" dev.ucplab.merchant.MerchantServer "$PORT" >/dev/null 2>&1 &
PID=$!; trap 'kill $PID 2>/dev/null || true' EXIT
for i in $(seq 1 50); do curl -s -o /dev/null "$B/.well-known/ucp" && break; sleep 0.1; done
H=(-H "Request-Id: 11111111-1111-4111-8111-111111111111" -H "UCP-Agent: profile=\"https://agent.example/.well-known/ucp\"" -H "Content-Type: application/json")
post() { local name="$1" path="$2" key="$3" body="$4"; curl -s -o "$OUT/$name.json" -w "$name %{http_code}\n" "${H[@]}" -H "Idempotency-Key: $key" -X POST "$B/ucp/v1$path" -d "$body"; }
lab() { curl -s -o /dev/null -X POST "$B/_lab/$1" -d "$2"; }
PAY_OK='{"payment":{"instruments":[{"id":"pi_1","handler_id":"mock_pay_1","type":"mock_card","credential":{"type":"MOCK","token":"tok_ok"}}]}}'
PAY_NO='{"payment":{"instruments":[{"id":"pi_1","handler_id":"mock_pay_1","type":"mock_card","credential":{"type":"MOCK","token":"tok_decline"}}]}}'
EMAIL='"buyer":{"email":"a@example.com"}'
lab reset '{}'
# Discovery: the merchant derives its endpoint from the Host header, so the random port is rewritten to a fixed one.
curl -s "${H[@]}" "$B/.well-known/ucp" | sed "s/127.0.0.1:$PORT/127.0.0.1:8081/g" > "$OUT/discovery.json"
post create_ready /checkout-sessions k1 "{$EMAIL,\"line_items\":[{\"item\":{\"id\":\"item_123\"},\"quantity\":2}]}"
post create_incomplete_no_buyer /checkout-sessions k2 '{"line_items":[{"item":{"id":"item_456"},"quantity":1}]}'
post create_partial_unavailable /checkout-sessions k3 "{$EMAIL,\"line_items\":[{\"item\":{\"id\":\"item_123\"},\"quantity\":1},{\"item\":{\"id\":\"no_such_item\"},\"quantity\":1}]}"
post create_all_unavailable /checkout-sessions k4 "{$EMAIL,\"line_items\":[{\"item\":{\"id\":\"no_such_item\"},\"quantity\":1}]}"
curl -s -o "$OUT/get_ready.json" -w "get_ready %{http_code}\n" "${H[@]}" "$B/ucp/v1/checkout-sessions/chk_1001"
curl -s -o "$OUT/error_not_found.json" -w "error_not_found %{http_code}\n" "${H[@]}" "$B/ucp/v1/checkout-sessions/chk_9999"
post error_idempotency_conflict /checkout-sessions k1 "{$EMAIL,\"line_items\":[{\"item\":{\"id\":\"item_123\"},\"quantity\":3}]}"
curl -s -o "$OUT/error_missing_header.json" -w "error_missing_header %{http_code}\n" -H "Request-Id: x" -X POST "$B/ucp/v1/checkout-sessions" -d '{}'
# price change before complete: chk_1001 is ready; change the price, then complete.
lab price '{"item_id":"item_123","price":2600}'
post complete_price_changed /checkout-sessions/chk_1001/complete c1 "$PAY_OK"
lab price '{"item_id":"item_123","price":2500}'
# decline
post create_for_decline /checkout-sessions k5 "{$EMAIL,\"line_items\":[{\"item\":{\"id\":\"item_456\"},\"quantity\":1}]}"
CD=$(python3 -I -c "import json;print(json.load(open('$OUT/create_for_decline.json'))['id'])")
post complete_declined "/checkout-sessions/$CD/complete" c2 "$PAY_NO"
# out of stock at complete
post create_for_oos /checkout-sessions k6 "{$EMAIL,\"line_items\":[{\"item\":{\"id\":\"item_456\"},\"quantity\":2}]}"
CO=$(python3 -I -c "import json;print(json.load(open('$OUT/create_for_oos.json'))['id'])")
lab stock '{"item_id":"item_456","stock":1}'
post complete_out_of_stock "/checkout-sessions/$CO/complete" c3 "$PAY_OK"
lab stock '{"item_id":"item_456","stock":50}'
# completed, with an order
post create_for_complete /checkout-sessions k7 "{$EMAIL,\"line_items\":[{\"item\":{\"id\":\"item_123\"},\"quantity\":2}]}"
CC=$(python3 -I -c "import json;print(json.load(open('$OUT/create_for_complete.json'))['id'])")
post complete_completed "/checkout-sessions/$CC/complete" c4 "$PAY_OK"
# in progress: hold the charge, read meanwhile
post create_for_progress /checkout-sessions k8 "{$EMAIL,\"line_items\":[{\"item\":{\"id\":\"item_123\"},\"quantity\":1}]}"
CP=$(python3 -I -c "import json;print(json.load(open('$OUT/create_for_progress.json'))['id'])")
lab config '{"payment_latency_ms":1500}'
curl -s -o /dev/null "${H[@]}" -H "Idempotency-Key: c5" -X POST "$B/ucp/v1/checkout-sessions/$CP/complete" -d "$PAY_OK" &
CURL_PID=$!
sleep 0.4
curl -s -o "$OUT/get_in_progress.json" -w "get_in_progress %{http_code}\n" "${H[@]}" "$B/ucp/v1/checkout-sessions/$CP"
wait $CURL_PID; lab config '{"payment_latency_ms":0}'
# cancel
post create_for_cancel /checkout-sessions k9 "{$EMAIL,\"line_items\":[{\"item\":{\"id\":\"item_456\"},\"quantity\":1}]}"
CX=$(python3 -I -c "import json;print(json.load(open('$OUT/create_for_cancel.json'))['id'])")
post cancel_canceled "/checkout-sessions/$CX/cancel" x1 '{}'
rm -f "$OUT/create_for_decline.json" "$OUT/create_for_oos.json" "$OUT/create_for_complete.json" "$OUT/create_for_progress.json" "$OUT/create_for_cancel.json"
# The merchant builds some objects with Map.of, whose key order differs between JVM runs. Rewrite every fixture with
# sorted keys and no extra whitespace so a regeneration is byte-stable and the git diff shows real changes only.
python3 -I - "$OUT" <<'PY'
import json, pathlib, sys
for f in sorted(pathlib.Path(sys.argv[1]).glob("*.json")):
    f.write_text(json.dumps(json.loads(f.read_text()), sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n")
PY
