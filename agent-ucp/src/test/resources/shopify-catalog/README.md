# Global Catalog fixtures (real catalog, stage 0)

Approved by the owner on 2026-11-19. This is a folder of its own: the H5 folder's
rule "Never copy the spike's global catalog answer" stays as it is, and these files follow their own strip, below.

**Source:** the owner's re-run of `scripts/shopify-catalog-spike.sh` on 2026-11-19 (`notes/catalog/recordings/`,
git-ignored). The first run's copy was lost with its worktree. Every answer came at `ucp.version` 2026-04-08, the
version of Shopify's sample agent profile.

| File | What it is | Cut |
| --- | --- | --- |
| `global_search_us.json` | `search_catalog`, US/USD, `ships_to` US, available only, query `snowboard` | the first 3 of 10 products, each with its one recorded variant; `pagination` kept (`has_next_page: true`, `total_count: 761`) |
| `global_get_product_us.json` | `get_product` of the US search's first variant, with the US context | whole |
| `global_lookup.json` | `lookup_catalog` of the first variant of each search (IN and US) | 2 products |

The `tools/list` answer and the IN/INR answers are not kept. No test needs them, and the IN answers hold foreign
shops' prices in other currencies.

## The strip

**Replaced with our own fakes:**
- every seller:
  - `name` becomes `Example Shop A`, `B`, …;
  - `domain` becomes `shop-a.myshopify.example`;
  - `url` becomes `https://www.shop-a.example/`;
  - `id` becomes `gid://shopify/Shop/1000000001`, …;
  - each policy link's URL becomes `https://checkout.shopify.com/...`, keeping its `type`;
- every title, as `Example Snowboard 1`, `2`, …, and its handle;
- every `description.plain`, as `<p>Our own test text for board N.</p>`. The recorded `<p>` markup in a *plain*
  description is kept, because the catalog really sends it;
- `metadata.top_features` and `unique_selling_points` (Inferred fields), as one line of our own each;
- every id:
  - the UPID becomes `gid://shopify/p/FAKEUPID000001`;
  - the variant becomes `gid://shopify/ProductVariant/1000001`;
  - `seller_product_id` becomes `gid://shopify/Product/2000001`;
  - `inputs[].id` follows its variant;
- the pagination `cursor`, as `FAKECURSOR0001`. The recorded one was base64 JSON with an offset and a count.

**Removed:** `media`, on products and variants, as in the H5 strip.

**Kept as recorded:** the JSON-RPC envelope (`jsonrpc`, `id`, `result.structuredContent`; the catalog sent no
`content[]`), the `ucp` block, the prices and currencies, `price_range`, `availability`, `eligible.native_checkout`
(false), `condition`, `rating`, `options`, `selected` and the option labels (sizes).

**Built in the recorded shape, with fake values.** The recordings keep only the host of each URL, so the spike saw
these shapes but not their values:
- `checkout_url` is `https://www.shop-a.example/buy/1000001:1?_gsid=FAKEGSID0001&attribution%2Fclick_id_tag=…&attribution%2Fclick_id_value=…&attribution%2Futm_medium=…&attribution%2Futm_source=…&ucp%2Fversion=2026-04-08`.
  The path is the spike's `/buy/<id>:1` (C1), and the keys are the six the spike reported. **The real encoding of the
  keys' slashes was not recorded.** The agent passes the URL through unchanged and never parses or edits it, so the
  encoding doesn't matter to any test.
- The product `url` is `https://www.shop-a.example/products/<handle>?variant=<id>`.

**The C3 relation is kept.** Each seller's `domain` (`shop-a.myshopify.example`) differs from the host of its `url`
and of its buy links (`www.shop-a.example`), as with every recorded offer. Tests of the host rule start from these
files and change one host.

## Rules (`ShopifyCatalogFixturesTest` scans every file here, this one included)

- Every host is a fake under `.example`, or `checkout.shopify.com`, `shopify.dev` or `ucp.dev`. There is no
  real store host: every seller domain ends in `.myshopify.example`.
- There is no media URL (Shopify's CDN), no IP literal, no Shopify access-token prefix, and no
  base64 cursor.
- No seller name, product title, brand or description text from a real store appears.
- The tracking values are fakes (`FAKEGSID…`, `FAKECLICK…`). The leak scan's branch pass reports their shapes as fakes
  ("info"), never as a hit.
