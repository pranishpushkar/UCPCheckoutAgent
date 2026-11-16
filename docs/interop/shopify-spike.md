# The Shopify spike (H1 C11)

`scripts/shopify-spike.sh` and `scripts/shopify_spike.py` (Python 3 standard library, always run with `python3 -I`) make one
real round trip against a Shopify **development store you own**: a token, one read-only search of the global catalog, the
store's profile, one read-only search at the store itself, one checkout with an item from that search, and a cancel. **You
run it. No CI job and no agent runs it.** It is tested only against a local fake (`python3 -I scripts/shopify_spike_test.py`,
20 tests; not part of `./mvnw verify`, which needs no Python). H5 Stage 0 fixed it before its first real run: the Keychain
items, answers wrapped in `result.structuredContent`, the item taken from the store's own search, the buyer IP made optional,
the query, `--record`, and the MCP endpoint read from the profile's `ucp.services` (the first real run showed the shape).

## What you must do first
1. In the Shopify Dev Dashboard, under Agents, verify the organization (Stripe) and create an agent identity. Copy its API key
   (the client id) and client secret.
2. Put both in the macOS Keychain, typing or pasting the value at the prompt so it is never on a command line or in shell
   history: `security add-generic-password -a "$USER" -s ucplab-shopify-catalog-id -w` and the same with
   `-s ucplab-shopify-catalog-secret`.
3. Have a **development store you own** (H5: `ucplab-dev.myshopify.com`). VERIFY: whether its own UCP endpoint answers while
   the store is private (password-protected); the global catalog does not list a private dev store.
4. An agent profile URL. For the spike, Shopify's documented sample
   (`https://shopify.dev/ucp/agent-profiles/2026-04-08/valid-with-capabilities.json`, H5 decision 5); H5 later hosts our
   own (no secret in it).
5. Export `SHOPIFY_STORE_DOMAIN` (your store) and `AGENT_PROFILE_URL`; optionally `SPIKE_QUERY` (default `snowboard`, which
   matches a dev store's generated test data). `BUYER_IP` (your own public IP) only if you pass `--with-buyer-ip`. The client
   id and secret are read from the Keychain by the wrapper, never from your shell.

## The commands
```
scripts/shopify-spike.sh --dry-run --i-own-this-store                          # prints every call, secrets replaced
scripts/shopify-spike.sh --i-own-this-store --record notes/h5/spike            # the real run, answers kept (redacted)
```
`--dry-run` makes no network call and needs no Keychain entry. `--i-own-this-store` is required: without it the script refuses
(DESIGN 26.5). It also refuses if the MCP endpoint in the store's profile is not on `SHOPIFY_STORE_DOMAIN`, and it takes the
checkout's item only from the store's own search (skipping any variant whose seller is another store), so a checkout is never
created at, or for, a store a global search returned. If the store's search finds nothing, it stops before any create.
`--with-buyer-ip` sends `Shopify-Buyer-IP` on every call to the store (its search refuses without it), never to the global catalog; without it no buyer data is sent (H5 decision 6). If Shopify refuses a create
without it, that is a finding for the owner, not a default. `--record DIR` writes each answer as JSON, redacted, with
`continue_url` and `checkout_url` cut to their host; the token answer is never written. Keep it under `notes/` (never
committed).

## Secrets
They come from the environment only. They are never an argument, never in a file, never printed: every line goes through
`redact()`, which replaces the client id and secret, the access token and the buyer IP with `<redacted>`. The test puts canary
values in the environment, runs `--dry-run` and a full run against the fake, and asserts that none appears in the output. The
hosted agent profile holds none. The `continue_url` is printed to the terminal only (it is for the buyer, you) and only if it is `https:`.

## What each step proves
| Step | Proves |
| --- | --- |
| token | the Catalogs key works with `client_credentials` |
| catalog search (global) | the API key path answers a read-only search with the shape the Dev Dashboard preview showed |
| store profile | a real store's `/.well-known/ucp` names an MCP endpoint, versions and capabilities (the script prints its keys) |
| catalog search (the store) | the store's own endpoint searches its products; the checkout's item comes from here |
| `create_checkout` | the call is accepted with `Authorization` and the agent profile (and `Shopify-Buyer-IP` only if asked); `continue_url` appears (the handoff is real). An answer with no checkout id prints a WARNING that a checkout may be open |
| `cancel_checkout` | what we created can be cancelled; it runs in a `finally`, so after any failure after the create |

It never calls `complete_checkout`: that is granted case by case, so the run **ends at the `continue_url` handoff** unless
the grant exists, and it sends no card, payment object or address. It does not prove completion, that our REST gateway can be
reused (this is MCP), or anything at a store you do not own. If a cancel fails the script prints a WARNING naming the
checkout, so you can cancel it by hand.
