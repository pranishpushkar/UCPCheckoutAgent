#!/usr/bin/env bash
# H5 Stage 3: one live run at the owner's own development store (docs/interop/shopify-live.md). Run by the OWNER, never
# by CI. The client id and secret come from the macOS Keychain (the spike's names) straight into the script's
# environment: never an argument, never a file, never printed. Everything else is read from the environment.
# Use --dry-run first: it reads no Keychain, calls nothing and starts nothing.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ " $* " != *" --dry-run "* ]]; then
  SHOPIFY_CLIENT_ID="$(security find-generic-password -a "$USER" -s ucplab-shopify-catalog-id -w)" \
  SHOPIFY_CLIENT_SECRET="$(security find-generic-password -a "$USER" -s ucplab-shopify-catalog-secret -w)" \
  exec python3 -I scripts/shopify_live.py "$@"
fi
exec python3 -I scripts/shopify_live.py "$@"
