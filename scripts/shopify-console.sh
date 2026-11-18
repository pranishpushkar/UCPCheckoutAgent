#!/usr/bin/env bash
# The lab console against the owner's own development store (docs/demo.md, "Handoff at a real store"). Run by the
# OWNER, never by CI. It is scripts/shopify-live.sh --console: the same guards (--i-own-this-store, --with-buyer-ip, the
# owned store alone), the client id and secret read from the Keychain only on a real run, and the owed-cancel drain on
# exit. The service listens on 127.0.0.1 only and runs until Ctrl-C; the owner signs in, asks and approves in the
# browser. A HANDED_OFF checkout is left open on exit. Use --dry-run first: it reads no Keychain, calls nothing and
# starts nothing.
set -euo pipefail
exec "$(dirname "$0")/shopify-live.sh" --console "$@"
