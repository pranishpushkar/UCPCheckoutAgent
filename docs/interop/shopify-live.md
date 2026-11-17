# The Shopify live run (H5 Stage 3)

`scripts/shopify-live.sh` and `scripts/shopify_live.py` (Python 3 standard library, always run with `python3 -I`) run the
agent once, end to end, against a Shopify **development store you own**: search, quote, your approval, hand off. **You
run it. No CI job runs it**, and nothing in `./mvnw verify` needs it, Python or the network. It reuses the spike's
redaction, sanitizing and call (`scripts/shopify_spike.py`, [shopify-spike.md](shopify-spike.md)) unchanged.

## What you must do first

1. The spike's Keychain items (`ucplab-shopify-catalog-id`, `ucplab-shopify-catalog-secret`) and its agent identity. The
   wrapper reads them only when it runs for real, straight into the script's environment.
2. Export `SHOPIFY_STORE_DOMAIN` (your store; it must end in `.myshopify.com`), `AGENT_PROFILE_URL` (Shopify's 2026-04-08
   sample profile, as in the spike) and `BUYER_IP` (your own public IP).
3. Build the service: `./mvnw -B -ntp -DskipTests package`.

## The commands

```
scripts/shopify-live.sh --dry-run --i-own-this-store --with-buyer-ip --cancel-after    # prints every step, secrets replaced
scripts/shopify-live.sh --i-own-this-store --with-buyer-ip --cancel-after             # the real run
```

- `--dry-run` reads no Keychain item, makes no network call and starts nothing.
- `--i-own-this-store` (DESIGN 26.5) and `--with-buyer-ip` (the store refuses every call without the IP) are required.
- `--cancel-after` cancels the handed-off checkout once you have looked at the store's page (VERIFY 5: whether cancel
  works on a handed-off checkout). Without it the checkout stays open until you cancel it in the store admin or it
  expires.
- `--approve` approves each card without asking, for a run with no terminal. Without it the script shows each card and
  asks.
- `--query` (default `snowboard`), `--port` (default 8080), `--record DIR` and `--evidence DIR` (defaults under
  `notes/h5/stage3/`, never committed).

## What it does

1. Writes a temporary `merchants.yaml` with exactly one merchant: `transport: mcp`, `completion: handoff`,
   `owned: true`, `shop_domain` from `SHOPIFY_STORE_DOMAIN`, `credential_ref: env:SHOPIFY_CLIENT`,
   `requires_buyer_ip: true`, `ucp_versions: ["2026-04-08"]`. Also a temporary policy file with a cap above the store's
   prices, and a temporary SQLite database. All three are deleted on exit, on failure too.
2. Starts the service (`java -jar`) in the `local` profile with `AGENT_MODEL=scripted`, so the run is the same each time
   and costs nothing. The service's environment is built from nothing: the client id and secret reach it as
   `SHOPIFY_CLIENT`, never as an argument.
3. Signs in as `alice`, starts a run with the query, and waits for the intent to reach `AWAITING_APPROVAL`.
4. Records the store's answers that the service does not keep raw (it stores status, currency, total and lines only):
   - `get_checkout` of the agent's own checkout right after its quote, and again about 60 s later;
   - a probe checkout of the same lines: `create_checkout`, `get_checkout` at once and about 60 s later,
     `update_checkout` with the same lines, then `cancel_checkout`. The probe is never completed.
   If the quote opens no approval, the script replays the agent's last `create_checkout` or `update_checkout` with
   the agent's own idempotency key and body, records the request and the store's raw answer, cancels any checkout the
   answer names, and stops. A store that honours the key answers as it did the first time and creates nothing new.
5. Shows each approval card and approves it, up to 4 cards (one more than the re-quote cap allows, so a cap that does
   not hold is seen). Waits up to 600 s for the intent to end.
6. Prints the result: the state, the failure reason if any, approvals given and stored, `requote_count`. On every way
   out, a failed quote included, saves `intent.json`, `events.json` and `attempts.json` (each attempt's request body and
   the outcome the service stored, sanitized and redacted) beside the service log, before the temporary database is
   deleted. Each create or update the script sends is recorded with its request (`<name>.request.json`).
7. In `HANDED_OFF`, prints the handoff URL **once, to the terminal, for the buyer only**. It is never written to a
   file or log.
8. With `--cancel-after`, cancels the agent's checkout after you press Enter.
9. Before stopping the service, waits up to 30 s for the run's owed cancels (7.6: the recovery worker sends each one,
   every 5 s, up to three times) to drain. A checkout still owed at the bound, or one the service gave up on
   (`cancel_gave_up`, `orphan_probe_gave_up`), is printed with its key masked, for you to cancel in the store admin.
   The wait cannot be cut short by Ctrl-C, since it is part of the cleanup.

## What is sent, and to whom

- To `api.shopify.com`: the client id and secret, for an access token (by the script and by the service, each its own).
- To your store only (the profile's MCP endpoint must be on `SHOPIFY_STORE_DOMAIN`, or the script refuses): the access
  token, `Shopify-Buyer-IP` with `BUYER_IP`, and the calls above. No card, payment object, address or `complete`
  (N1).
- `BUYER_IP` is your own public IP. The store needs it on every call. It goes to no one else, and is never printed or
  recorded.

## What is never written

The client id or secret, the access token, the buyer IP, the session cookie, the CSRF token, an approval token, the
approval secret, or the handoff URL. Recorded answers go through the spike's `sanitize()` and `redact()`:
`continue_url` and `checkout_url` are cut to their host, and every `?key=` is masked. Recordings and evidence go under
`notes/` (git-ignored). A recording becomes a fixture only after the F strip (store host replaced, ids faked), since
`ShopifyFixturesTest` scans every file in `agent-ucp/src/test/resources/shopify/`.

## Checks by hand (no Python in verify)

1. `--dry-run` without `--i-own-this-store` refuses; without `--with-buyer-ip` refuses; with a domain not ending in
   `.myshopify.com` refuses.
2. `--dry-run` with canary values in `SHOPIFY_CLIENT_ID`, `SHOPIFY_CLIENT_SECRET` and `BUYER_IP`: none appears in the
   output; every header and credential reads `<redacted>`.
3. After the real run: `scripts/h5-leak-scan.sh` over the evidence and recordings directories finds nothing.
