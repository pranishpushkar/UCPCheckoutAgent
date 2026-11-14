# The Shopify spike (H1 C11)

`scripts/shopify-spike.sh` and `scripts/shopify_spike.py` (Python 3 standard library, always run with `python3 -I`) make one
real round trip against a Shopify **development store you own**: a token, one read-only catalog search, the store's profile,
one checkout, and a cancel. **You run it. No CI job and no agent runs it, and it has not been run against Shopify.** It was
built and tested only against a local fake (`python3 -I scripts/shopify_spike_test.py`, six tests; not part of `./mvnw verify`,
which needs no Python).

## What you must do first
1. In the Shopify Dev Dashboard create a **Catalogs API key** (Catalogs, Get an API key). Copy the client id and secret.
2. Put both in the macOS Keychain, typing the value at the prompt so it is never on a command line or in shell history:
   `security add-generic-password -a "$USER" -s shopify-spike-client-id -w` and the same with `-s shopify-spike-client-secret`.
3. Have a **development store you own** with UCP checkout enabled (VERIFY: the research found no documented way to make a dev
   store the target of an agent checkout).
4. Host an agent profile JSON at a public HTTPS URL (GitHub Pages, a public repository through `raw.githubusercontent.com`, or
   Cloudflare Pages). It holds no secret:
   `{"ucp": {"version": "2026-08-25", "capabilities": {"dev.ucp.shopping.cart": [{"version": "2026-08-25"}], "dev.ucp.shopping.checkout": [{"version": "2026-08-25"}]}}}`
5. Export `SHOPIFY_STORE_DOMAIN` (your store, for example `my-store.myshopify.com`), `AGENT_PROFILE_URL`, `BUYER_IP` (your own
   public IP) and, for the checkout, `SPIKE_ITEM_ID` (an item id from the catalog search). The client id and secret are read from
   the Keychain by the wrapper, never from your shell.

## The commands
```
scripts/shopify-spike.sh --dry-run --i-own-this-store     # prints every call, with each secret, the token and the IP replaced
scripts/shopify-spike.sh --i-own-this-store               # the real run
```
`--dry-run` makes no network call and needs no Keychain entry. `--i-own-this-store` is required: without it the script refuses
(DESIGN 26.5). It also refuses if the MCP endpoint in the store's profile is not on `SHOPIFY_STORE_DOMAIN`, so a checkout is
never created at a store a search returned.

## Secrets
They come from the environment only. They are never an argument, never in a file, never printed: every line goes through
`redact()`, which replaces the client id and secret, the access token and the buyer IP with `<redacted>`. The test puts canary
values in the environment, runs `--dry-run` and a full run against the fake, and asserts that none appears in the output. The
hosted agent profile holds none. The `continue_url` is printed to the terminal only (it is for the buyer, you) and only if it is `https:`.

## What each step proves
| Step | Proves |
| --- | --- |
| token | the Catalogs key works with `client_credentials` |
| catalog search | the global catalog MCP answers a read-only search with the shape we assume |
| store profile | a real store's `/.well-known/ucp` names an MCP endpoint and capabilities |
| `create_checkout` | the call is accepted with `Authorization`, `Shopify-Buyer-IP` and the agent profile; `continue_url` appears (the handoff is real) |
| `cancel_checkout` | what we created can be cancelled; it runs in a `finally`, so after any failure after the create |

It never calls `complete_checkout`: that is granted case by case, so the run **ends at the `continue_url` handoff** unless
the grant exists, and it sends no card, payment object or address. It does not prove completion, that our REST gateway can be
reused (this is MCP), or anything at a store you do not own. If a cancel fails the script prints a WARNING naming the
checkout, so you can cancel it by hand.
