#!/usr/bin/env bash
# Real catalog, stage 0 (notes/real-catalog-proposal.md): a read-only spike of Shopify's Global Catalog, settling
# VERIFY C1-C6. Run by the OWNER, never by CI or an agent. It calls no store. The client id and secret come from the
# macOS Keychain (the H5 spike's items) straight into the script's environment: never an argument, never a file, never
# printed. AGENT_PROFILE_URL is read from the environment. Use --dry-run first: it reads no Keychain and calls nothing.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ " $* " != *" --dry-run "* ]]; then
  SHOPIFY_CLIENT_ID="$(security find-generic-password -a "$USER" -s ucplab-shopify-catalog-id -w)" \
  SHOPIFY_CLIENT_SECRET="$(security find-generic-password -a "$USER" -s ucplab-shopify-catalog-secret -w)" \
  exec python3 -I scripts/shopify_catalog_spike.py "$@"
fi
exec python3 -I scripts/shopify_catalog_spike.py "$@"
