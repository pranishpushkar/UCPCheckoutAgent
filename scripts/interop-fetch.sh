#!/usr/bin/env bash
# Fetches the official UCP sample merchants at the commit pinned in docs/interop/pin.txt (H1, notes/h1/h1-plan.md C1).
# Clones into eval-harness/target/interop/samples (git-ignored), checks out the pin, fails unless HEAD is exactly the pin,
# then installs the Python sample's dependencies from OUR lock file, docs/interop/uv.lock (the sample's server directory has
# none): `uv sync --frozen` never updates it, and its hashes pin every package. Runs nothing else: no sample code is executed.
#   PIN_FILE=path scripts/interop-fetch.sh     uses another pin file (the planted-hash test does)
set -euo pipefail
cd "$(dirname "$0")/.."
PIN_FILE="${PIN_FILE:-docs/interop/pin.txt}"
pin() { sed -n "s/^$1=//p" "$PIN_FILE" | head -1; }
SAMPLES_REPO="$(pin SAMPLES_REPO)"; SAMPLES_SHA="$(pin SAMPLES_SHA)"; UV_VERSION="$(pin UV_VERSION)"
PYPROJECT_SHA="$(pin SERVER_PYPROJECT_SHA256)"
if [ -z "$SAMPLES_REPO" ] || [ -z "$SAMPLES_SHA" ] || [ -z "$UV_VERSION" ] || [ -z "$PYPROJECT_SHA" ]; then
  echo "FAIL: $PIN_FILE lacks SAMPLES_REPO, SAMPLES_SHA, UV_VERSION or SERVER_PYPROJECT_SHA256" >&2; exit 1
fi

command -v uv >/dev/null || { echo "FAIL: uv is not installed (https://docs.astral.sh/uv/)" >&2; exit 1; }
HAVE_UV="$(uv --version | awk '{print $2}')"
[ "$HAVE_UV" = "$UV_VERSION" ] || { echo "FAIL: uv $HAVE_UV is installed, the pin says $UV_VERSION" >&2; exit 1; }

DIR="eval-harness/target/interop/samples"
SERVER="$DIR/rest/python/server"
mkdir -p "$(dirname "$DIR")"
if [ ! -d "$DIR/.git" ]; then git clone -q "$SAMPLES_REPO" "$DIR"; fi
git -C "$DIR" fetch -q origin
if ! git -C "$DIR" checkout -q --detach "$SAMPLES_SHA" 2>/dev/null; then
  echo "FAIL: the pinned commit $SAMPLES_SHA is not in $SAMPLES_REPO" >&2; exit 1
fi
HEAD_SHA="$(git -C "$DIR" rev-parse HEAD)"
if [ "$HEAD_SHA" != "$SAMPLES_SHA" ]; then
  echo "FAIL: the clone is at $HEAD_SHA, the pin says $SAMPLES_SHA" >&2; exit 1
fi
if [ -n "$(git -C "$DIR" status --porcelain | grep -v '^?? rest/python/server/uv.lock$' || true)" ]; then
  echo "FAIL: the clone has local changes; delete $DIR and run again" >&2; exit 1
fi
HAVE_PYPROJECT="$(shasum -a 256 "$SERVER/pyproject.toml" | cut -d' ' -f1)"
if [ "$HAVE_PYPROJECT" != "$PYPROJECT_SHA" ]; then
  echo "FAIL: the sample's pyproject.toml changed ($HAVE_PYPROJECT, pin $PYPROJECT_SHA): make docs/interop/uv.lock again on purpose" >&2; exit 1
fi
cp docs/interop/uv.lock "$SERVER/uv.lock"
(cd "$SERVER" && uv sync --frozen)
echo "OK: samples at $HEAD_SHA, dependencies installed from docs/interop/uv.lock (uv $HAVE_UV)"
