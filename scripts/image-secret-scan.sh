#!/usr/bin/env bash
# m6-plan section 9.4, I8: no image layer holds a secret. Run from the repo root with Docker running:
#   scripts/image-secret-scan.sh            positive control, then build and scan the three images
#   scripts/image-secret-scan.sh --control  only the positive control (a throwaway image that copies a canary)
#
# It plants canaries where a careless Dockerfile or build context could pick them up: a .env with a canary key and a
# canary secret, a canary file in data/ and one in the repo root. It builds the images (docker compose build), exports
# each with `docker save`, unpacks every layer, and searches every file, the image config, `docker history` and
# `docker inspect` for each canary, and for `GEMINI_API_KEY=` or `APPROVAL_SECRET=` followed by a value. A hit prints the
# image and the file or layer, never the value, and fails the script. The planted files are removed afterwards and an
# existing .env is put back.
set -euo pipefail
cd "$(dirname "$0")/.."

WORK=$(mktemp -d)
PLANTED=()
BACKUP=""
cleanup() {
  for f in "${PLANTED[@]:-}"; do [ -n "$f" ] && rm -f "$f"; done
  if [ -n "$BACKUP" ]; then mv "$BACKUP" .env; fi
  rmdir data 2>/dev/null || true
  docker rmi -f ucplab/scan-control:local >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
canary() { echo "canary-$1-$(openssl rand -hex 12)"; }

# Searches one image for every canary. Prints "<image>: <what> holds <canary name>" for each hit; returns 1 on a hit.
# $1 image  $2 dir for the export  then pairs "name=value"
scan_image() {
  local image=$1 dir=$2; shift 2
  local hits=0 name value tarball="$dir/image.tar" n=0
  mkdir -p "$dir/export" "$dir/layers"
  docker save -o "$tarball" "$image"
  tar -xf "$tarball" -C "$dir/export"
  # Every file of the export that is a tar (a layer, gzip or plain) is unpacked next to it.
  while IFS= read -r -d '' f; do
    if tar -tf "$f" >/dev/null 2>&1; then
      n=$((n + 1)); mkdir -p "$dir/layers/$n"; tar -xf "$f" -C "$dir/layers/$n" 2>/dev/null || true
      echo "$dir/layers/$n=${f#"$dir"/export/}" >> "$dir/layer-names"
    fi
  done < <(find "$dir/export" -type f -print0)
  docker history --no-trunc "$image" > "$dir/history.txt" 2>&1
  docker inspect "$image" > "$dir/inspect.json" 2>&1
  for pair in "$@"; do
    name=${pair%%=*}; value=${pair#*=}
    # -a: binary files too; -l: file names only, so the value is never printed.
    while IFS= read -r file; do
      echo "$image: $name found in ${file#"$dir"/}"; hits=$((hits + 1))
    done < <(grep -rlaF -- "$value" "$dir/export" "$dir/layers" "$dir/history.txt" "$dir/inspect.json" 2>/dev/null || true)
  done
  # A key or a secret assigned a value, in any file of any layer or in the image's config and history.
  while IFS= read -r file; do
    echo "$image: a GEMINI_API_KEY or APPROVAL_SECRET with a value in ${file#"$dir"/}"; hits=$((hits + 1))
  done < <(grep -rlaE -- '(GEMINI_API_KEY|APPROVAL_SECRET)=[^[:space:]"]+' "$dir/layers" "$dir/history.txt" "$dir/inspect.json" \
             "$dir/export/blobs" 2>/dev/null | grep -v '\.jar' || true)
  rm -rf "$dir/export" "$dir/layers" "$tarball"
  [ "$hits" -eq 0 ]
}

C_ROOT=$(canary root); C_DATA=$(canary data); C_KEY=$(canary key); C_SECRET=$(canary secret); C_MARK=$(canary marker)

echo "== positive control: a throwaway image that copies a canary file is found"
mkdir -p "$WORK/control"
echo "$C_ROOT" > "$WORK/control/canary.txt"
cat > "$WORK/control/Dockerfile" <<DOCKER
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515
COPY canary.txt /canary.txt
DOCKER
docker build -q -t ucplab/scan-control:local "$WORK/control" >/dev/null
if scan_image ucplab/scan-control:local "$WORK/control-scan" "the control's canary=$C_ROOT"; then
  fail "the scan did not find the canary in the control image: the scan is blind"
fi
echo "ok: the control's canary was found, as it must be"
[ "${1:-}" = "--control" ] && exit 0

echo "== plant canaries in the build context's neighbourhood"
[ -f .env ] && { BACKUP=$(mktemp); cp .env "$BACKUP"; }
{ echo "APPROVAL_SECRET=$C_SECRET"; echo "GEMINI_API_KEY=$C_KEY"; echo "CANARY_FILE_MARKER=$C_MARK"; } > .env
mkdir -p data
echo "$C_DATA" > data/canary-data.txt; PLANTED+=(data/canary-data.txt)
echo "$C_ROOT" > canary-root.txt; PLANTED+=(canary-root.txt)

echo "== build the three images (docker compose build)"
docker compose build >/dev/null

status=0
for image in ucplab/merchant:local ucplab/fault-proxy:local ucplab/agent-service:local; do
  echo "== scan $image"
  scan_image "$image" "$WORK/$(basename "${image%%:*}")" "the root canary file=$C_ROOT" "the data canary file=$C_DATA" \
    "the .env key=$C_KEY" "the .env secret=$C_SECRET" "the .env marker=$C_MARK" || status=1
done
[ "$status" -eq 0 ] || fail "an image holds a canary"
echo "ok: no canary, no key and no secret in any layer, config or history of the three images"
echo "SCAN PASSED"
