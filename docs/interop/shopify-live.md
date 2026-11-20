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
- `--cancel-after` cancels the handed-off checkout once you have looked at the store's page. It works (VERIFY 5,
  answered in Stage 3: HTTP 200, status `canceled`). Without it the checkout stays open until you cancel it in the store
  admin or it expires, about 30 days after it was last read.
- `--approve` approves each card without asking, for a run with no terminal. Without it the script shows each card and
  asks.
- `--query` (default `snowboard`), `--port` (default 8080), `--record DIR` and `--evidence DIR` (defaults under
  `notes/h5/stage3/`, never committed).
- `--console` (or `scripts/shopify-console.sh`) runs no scripted purchase. It leaves the service up for the browser
  until Ctrl-C, and you sign in, ask and approve on the console ([demo.md](../demo.md), section 8). The same guards
  apply, and `--approve`, `--cancel-after`, `--query` and `--record` are refused. On exit it keeps each intent's
  evidence under `notes/h5/console/evidence/<intent>/` and drains owed cancels. It never cancels a handed-off checkout,
  since you may be paying at the store.
- `--model gemini`, with `--console` only (DESIGN 26.6): the service runs the live model instead of the scripted one
  (the default). The key is `GEMINI_API_KEY` in your shell, never an argument: the script refuses to start, in one
  line, when it is unset, and refuses a jar without `agent-adk` (the live client) on its runtime classpath. The key
  goes into the service's environment only. It is never printed (the dry run shows `<redacted>`), and never written to
  a file, log or evidence. See [What reaches Gemini](#what-reaches-gemini) and [Cost](#cost-of-a-console-run-with-gemini).
- `--after-payment`, with `--console` only: on Ctrl-C, after the evidence and before the drain, one read-only
  `get_checkout` of each handed-off checkout, then another 60 s later. Each answer is recorded, redacted, as
  `notes/h5/console/evidence/<intent>/after_payment_get_checkout_{0s,60s}.json` (VERIFY 7: what the store says of a
  checkout the buyer paid for). Nothing is sent without the flag.
- Ctrl-C reaches the script alone, in both modes. The service runs in a session of its own, so the script can still
  save the evidence, run the after-payment reads and drain the owed cancels through it. The script then stops it:
  SIGTERM, then SIGKILL after 30 s. If the script itself is killed hard (`kill -9`), the service keeps running on
  `127.0.0.1:8080`. Stop it with `pkill -f agent-service-0.1.0-SNAPSHOT.jar`.
- The service listens on `127.0.0.1` only, in both modes: the local dev sign-in must not be reachable from the network.

## What it does

1. Writes a temporary `merchants.yaml` with exactly one merchant: `transport: mcp`, `completion: handoff`,
   `owned: true`, `shop_domain` from `SHOPIFY_STORE_DOMAIN`, `credential_ref: env:SHOPIFY_CLIENT`,
   `requires_buyer_ip: true`, `ucp_versions: ["2026-04-08"]`. Also a temporary policy file with a cap above the store's
   prices, and a temporary SQLite database. All three are deleted on exit, on failure too.
2. Starts the service (`java -jar`) in the `local` profile with `AGENT_MODEL=scripted`, so the run is the same each time
   and costs nothing. (`--console --model gemini` sets `AGENT_MODEL=gemini` and passes `GEMINI_API_KEY`; the scripted
   run always uses the scripted model.) The service's environment is built from nothing: the client id and secret reach it as
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
- To `generativelanguage.googleapis.com`, with `--model gemini` only: the key, in the API's authentication header, and
  what [What reaches Gemini](#what-reaches-gemini) lists.

## What reaches Gemini

With `--model gemini` (DESIGN 26.6), each model call sends the following.

- The system prompt (DESIGN 6.4, version v3). When the run has a budget, the system prompt is followed by the
  `[BUDGET]` hint: "The user's budget for this run is $700.00…". This is our own text, built from the run's stored
  budget.
- The conversation: your messages, and the model's own earlier turns.
- The five tool specs (DESIGN 6.2).
- The tool results:
  - search results and product details, which include the store's product titles and descriptions. These are
    **untrusted** text (S10), cleaned and wrapped as `<untrusted ...>`;
  - the proposal's state and quote, or the P9 refusal with the budget and the quoted total;
  - a handed-off intent's status, with the fixed note "handed to the store for payment".

It never gets:

- the Shopify client id, client secret or access token;
- the buyer IP;
- the handoff URL (`continue_url`) or a checkout id with its `?key=`;
- the store order's id or its confirmation code (`label`);
- an approval token, the session cookie or the CSRF token.

`GeminiBoundaryTest` (in `./mvnw verify`, offline) enforces this. It runs the real Gemini client against a loopback
stub of the API, through a whole handoff with a canary in each of those values, and scans every request the stub
receives.

The budget stays yours. Only P9 enforces it, in code. `propose_purchase` has no budget argument and refuses any
argument it does not know, so no model output can set, raise or clear the budget. The same test checks this under the
live client.

## Cost of a console run with Gemini

The limits that exist are per run (DESIGN 6.3, `AgentBudget.DEFAULT`): 6 model calls, 8 tool calls, 30 s of wall clock
(your time answering excluded) and 20,000 input plus output tokens. A run that reaches any of them ends with
`BUDGET_EXCEEDED`. There is no daily cap on model calls or tokens. The console's daily cap is P3's on purchases
(`daily_cap_minor: 400000`, 4,000.00 in the store's currency, in the temporary policy file), not on the model. The
console runs only while you keep it open, and each message you send is one run. Check Gemini's current pricing
yourself.

## What is never written

The client id or secret, the access token, the buyer IP, the Gemini API key, the session cookie, the CSRF token, an
approval token, the approval secret, or the handoff URL. Recorded answers go through the spike's `sanitize()` and `redact()`:
`continue_url` and `checkout_url` are cut to their host, and every `?key=` is masked. The intent's `handoff_url` in
`intent.json` is cut to its host too. Recordings and evidence go under
`notes/` (git-ignored). A recording becomes a fixture only after the F strip (store host replaced, ids faked), since
`ShopifyFixturesTest` scans every file in `agent-ucp/src/test/resources/shopify/`.

## Checks by hand (no Python in verify)

1. `--dry-run` without `--i-own-this-store` refuses; without `--with-buyer-ip` refuses; with a domain not ending in
   `.myshopify.com` refuses.
2. `--dry-run` with canary values in `SHOPIFY_CLIENT_ID`, `SHOPIFY_CLIENT_SECRET` and `BUYER_IP`: none appears in the
   output; every header and credential reads `<redacted>`.
3. After the real run: `scripts/h5-leak-scan.sh` over the evidence and recordings directories finds nothing.
4. After a console run with `--model gemini`: with `GEMINI_API_KEY` still exported,
   `scripts/h5-leak-scan.sh --evidence notes/h5/console/evidence` finds neither the key (a fixed pattern, fed to grep on
   a pipe) nor a key's shape in the evidence, and finds the key in no commit of the branch. `--dry-run` with
   `--model gemini` shows `"GEMINI_API_KEY": "<redacted>"`.
