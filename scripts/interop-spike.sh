#!/usr/bin/env bash
# The live spike of H1 C1 (notes/h1/h1-plan.md): runs the pinned UCP sample merchant on 127.0.0.1 and records what it does.
# Run scripts/interop-fetch.sh first. This runs THIRD-PARTY CODE (the sample's import_csv.py and server, through
# scripts/interop_serve.py), from the clone in eval-harness/target/interop, bound to 127.0.0.1, with temporary databases
# that a trap deletes on any exit. Never part of ./mvnw verify. Records docs/interop/fixtures/*.json (normalised).
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"
SERVER="$REPO/eval-harness/target/interop/samples/rest/python/server"
DATA="$REPO/eval-harness/target/interop/samples/rest/python/test_data/flower_shop"
[ -f "$SERVER/uv.lock" ] || { echo "FAIL: run scripts/interop-fetch.sh first" >&2; exit 1; }

TMP="$(mktemp -d "${TMPDIR:-/tmp}/interop-spike.XXXXXX")"
PID=""
cleanup() {
  if [ -n "$PID" ]; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT

PORT="$(python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')"
SECRET="interop-$(python3 -I -c 'import uuid;print(uuid.uuid4())')"

echo "== databases (import_csv.py)"
(cd "$SERVER" && uv run --frozen import_csv.py --products_db_path="$TMP/products.db" \
  --transactions_db_path="$TMP/transactions.db" --data_dir="$DATA") >"$TMP/import.log" 2>&1 || { tail -20 "$TMP/import.log" >&2; exit 1; }

echo "== server on 127.0.0.1:$PORT"
(cd "$SERVER" && exec uv run --frozen python "$REPO/scripts/interop_serve.py" "$TMP/products.db" "$TMP/transactions.db" "$PORT" "$SECRET") >"$TMP/server.log" 2>&1 &
PID=$!
for _ in $(seq 1 100); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/.well-known/ucp" && break
  kill -0 "$PID" 2>/dev/null || { echo "FAIL: the server exited" >&2; tail -20 "$TMP/server.log" >&2; exit 1; }
  sleep 0.2
done
curl -s -o /dev/null "http://127.0.0.1:$PORT/.well-known/ucp" || { echo "FAIL: the server did not answer" >&2; tail -20 "$TMP/server.log" >&2; exit 1; }

echo "== spike"
rm -f docs/interop/fixtures/*.json
python3 -I scripts/interop_spike.py "http://127.0.0.1:$PORT" "$TMP/products.db" "$TMP/transactions.db" docs/interop/fixtures
echo "== done: $(ls docs/interop/fixtures | wc -l | tr -d ' ') fixtures in docs/interop/fixtures"
