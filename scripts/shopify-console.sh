#!/usr/bin/env bash
# The lab console against the owner's own development store (docs/demo.md, "Handoff at a real store"). Run by the
# OWNER, never by CI. It is scripts/shopify-live.sh --console: the same guards (--i-own-this-store, --with-buyer-ip, the
# owned store alone), the client id and secret read from the Keychain only on a real run, and the owed-cancel drain on
# exit. The service listens on 127.0.0.1 only and runs until Ctrl-C; the owner signs in, asks and approves in the
# browser. A HANDED_OFF checkout is left open on exit. Use --dry-run first: it reads no Keychain, calls nothing and
# starts nothing. --model gemini (default scripted, DESIGN 26.6) runs the live model: GEMINI_API_KEY comes from your
# environment only, into the service's environment; never pass it as an argument. Without it the run is refused.
# --catalog (DESIGN 26.7) lists Shopify's Global Catalog alone instead of your store: the service reads the catalog with
# your client's token and calls no store, so it needs only AGENT_PROFILE_URL beside the Keychain's client, never the
# buyer IP, and refuses --i-own-this-store, --with-buyer-ip and --after-payment. An approved purchase gives a link to the
# seller's own site that you open yourself; the agent sees nothing after it.
set -euo pipefail
exec "$(dirname "$0")/shopify-live.sh" --console "$@"
