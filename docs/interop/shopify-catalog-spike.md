# The Global Catalog spike (real catalog, stage 0)

`scripts/shopify-catalog-spike.sh` and `scripts/shopify_catalog_spike.py` are a **read-only** spike of Shopify's
Global Catalog MCP (`https://catalog.shopify.com/api/ucp/mcp`). They settle VERIFY C1–C6 of
`notes/real-catalog-proposal.md` before any DESIGN change. The script is Python 3 standard library only, always run with
`python3 -I`. **You run it. No CI job runs it**, and nothing in `./mvnw verify` needs it, Python or the network. It
reuses the H5 spike's redaction and sanitizing ([shopify-spike.md](shopify-spike.md)) unchanged.

## Before you start

1. The H5 Keychain items (`ucplab-shopify-catalog-id` and `ucplab-shopify-catalog-secret`). The wrapper reads them only
   on a real run, straight into the script's environment.
2. Export `AGENT_PROFILE_URL`, the agent profile every catalog request names. To test C6, use Shopify's sample
   profile, as in H5.

## The commands

```
scripts/shopify-catalog-spike.sh --dry-run    # prints every call, secrets replaced; reads no Keychain, calls nothing
scripts/shopify-catalog-spike.sh              # the real run
```

`--query` (default `snowboard`) and `--record DIR` (default `notes/catalog/recordings/`, git-ignored).

## What it calls, and nothing else

The script makes these calls, in order:

1. `GET` the agent profile (C6: does it declare `dev.ucp.shopping.catalog.search`, `.lookup` and
   `dev.shopify.catalog.global`?).
2. `POST` `api.shopify.com` for one token-tier token (C5).
3. `tools/list` at the catalog (C1: does any tool take a quantity? C2: is `native_checkout` described?).
4. `search_catalog` twice, each filtered to `available` and `ships_to` that country:
   - `address_country: IN`, `currency: INR`, `ships_to: {country: IN}`;
   - `address_country: US`, `currency: USD`, `ships_to: {country: US}`.
5. `search_catalog` once more, US/USD, **without** a token (C4: what does an anonymous caller get?).
6. `lookup_catalog` of the first offer of each search.
7. `get_product` of the first offer of each search, in its own context (C1: the `checkout_url` path; C3: is the link on
   `seller.domain`?).

A guard refuses every other call before it is sent. It allows only that `GET`, `POST` to the token endpoint or the
catalog, `https` only, and the tools `search_catalog`, `lookup_catalog` and `get_product`.

The script calls **no store**. It never builds or follows a `checkout_url`, and it never sends a buyer IP. It waits
0.5 s between calls, far below the documented 5 requests per second.

## What it prints and records

- **For each context:** the number of products, offers and sellers, with each seller's name and domain, the
  currencies, `total_count`, `has_next_page`, `ucp.version` and any message codes. Then one line for each of C1–C6.
- **Recordings:** every answer under the recordings directory, sanitized and redacted. Every `checkout_url`, `url` and
  seller URL is cut to its host, so no attribution parameter is kept, and any other URL loses its query. The token
  answer is never recorded. `report.json` holds the report.

## Checks by hand

1. `--dry-run` with canary values in `SHOPIFY_CLIENT_ID` and `SHOPIFY_CLIENT_SECRET`: none appears, and the
   `Authorization` header reads `<redacted>`.
2. After the real run, `scripts/h5-leak-scan.sh --evidence notes/catalog/recordings` finds nothing.
3. Tests, against a local fake: `python3 -I scripts/shopify_catalog_spike_test.py`.

## Results (the owner's run, 2026-11-19)

Every call answered HTTP 200. The query was `snowboard`.

**Search, by context:**

- **US/USD, `ships_to` US:** 760 results, all priced in USD. Sellers included Burton, evo, Wiredsport, THE SKI
  MONSTER, Ski Pro AZ, Surface2Air and shopnomads.
- **IN/INR, `ships_to` IN:** 789 results, priced in a mix of AUD, CAD and INR, from foreign shops. The currency in
  `context` does not convert the prices.

**VERIFY, answered:**

| VERIFY | Answer | What follows |
| --- | --- | --- |
| C1 quantity | No tool takes a quantity. Every buy link is `/buy/<id>:1` | A link intent is quantity 1 only. The link is never built or edited |
| C2 `native_checkout` | `false` on all 34 offers seen | It plays no part. The agent never completes at these stores (N1) |
| C3 link host | 0 of 34 `checkout_url`s were on `seller.domain`. All were on the shop's own domain (`www.evo.com`, `www.burton.com`, …) | A `checkout_url` is accepted only if its host equals the host of the offer's `seller.url` or `seller.domain`. Otherwise the offer is refused |
| C4 anonymous | It works | Authenticated calls are used anyway, as Shopify asks |
| C5 the key | The H5 Dev Dashboard key works for the Global Catalog | The same Keychain items |
| C6 profile | Shopify's sample profile declares all three catalog capabilities | We don't need a profile of our own |

**The owner's choices:**

- Search context: `US`/`USD`, `ships_to` US, available only.
- The run's budget currency is USD.

**Leak scan:** `scripts/h5-leak-scan.sh --evidence notes/catalog/recordings` passed. Every evidence pattern counted 0:
the client secret, the client id, the buyer IP, the store with `/cart/c/`, an unredacted `?key=`, `Shopify-Buyer-IP`,
a bearer token, `session_token` and the Gemini key shape. The scan of the branch matched only the fakes in tests and
docs. The scan now also looks for the catalog's tracking parameters (`_gsid=`, `click_id_value=`, `click_id_tag=`), so
it shows that no full buy link was recorded.
