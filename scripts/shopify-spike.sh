#!/usr/bin/env bash
# H1 C11: the Shopify spike (docs/interop/shopify-spike.md). Run by the OWNER, never by CI or an agent. The client id and
# secret come from the macOS Keychain straight into the script's environment: never an argument, never a file, never printed.
# Everything else is read from the environment. Use --dry-run first.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ " $* " != *" --dry-run "* ]]; then
  SHOPIFY_CLIENT_ID="$(security find-generic-password -a "$USER" -s shopify-spike-client-id -w)" \
  SHOPIFY_CLIENT_SECRET="$(security find-generic-password -a "$USER" -s shopify-spike-client-secret -w)" \
  exec python3 -I scripts/shopify_spike.py "$@"
fi
exec python3 -I scripts/shopify_spike.py "$@"
