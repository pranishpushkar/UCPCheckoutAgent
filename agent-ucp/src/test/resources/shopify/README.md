# Shopify fixtures (H5 F)

Answers from the owner's Shopify development store, recorded on 2026-11-16 by the H5 spike
(`scripts/shopify_spike.py`, `docs/interop/shopify-spike.md`), then stripped for the repository. Every Shopify test
in `./mvnw verify` reads these files; none goes to the network.

| File | What it is | Version it came at |
| --- | --- | --- |
| `store_profile.json` | the store's `/.well-known/ucp` profile | declares 2026-08-25; lists 2026-01-23 and 2026-04-08 as supported |
| `catalog_search_store.json` | the store's own MCP catalog search, cut to 3 of its 10 products | 2026-04-08 |
| `create_checkout.json` | MCP `create_checkout` for one variant, no buyer | 2026-04-08 |
| `cancel_checkout.json` | MCP `cancel_checkout` of that checkout | 2026-04-08 |
| `catalog_search_store.error.json` | HTTP 422, JSON-RPC -32000: the search sent without the buyer IP header | none (an error) |

## The strip

- The store's host is replaced with `example-dev.myshopify.com`; no other store host appears.
- The checkout token is the fixed fake `fakeCheckoutToken0000001`, the cart line id a fixed fake UUID, and the policy
  link's shop and policy numbers fixed fakes. Every checkout key stays masked as `<redacted>`.
- Product and variant descriptions are one short line of our own words; titles, ids, prices and options are as
  recorded.
- Media are removed: the `media` lists and line items' `image_url`.
- In each MCP answer, `content[].text` is the stripped `structuredContent` serialized again, as Shopify sends it.

## Rules

- Never copy the spike's global catalog answer (`catalog_search_global.json`): it holds other stores' products and
  text.
- No real checkout key, no cart permalink path, no store host but the placeholder, no IP literal, and no Shopify access
  token prefix may appear in this folder. `ShopifyFixturesTest` scans every file here, this one included.
