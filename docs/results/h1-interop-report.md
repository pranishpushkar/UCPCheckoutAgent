# H1 interop report

| row | runs | terminal states | orders (sample's own table) | violations | time | agreement with the lab |
| --- | --- | --- | --- | --- | --- | --- |
| S0 | 20 | {COMPLETED=20} | 20 | 0 | 6.1 s | agrees |
| S1 | 20 | {COMPLETED=20} | 20 | 0 | 7.0 s | agrees |
| S2 | 20 | {COMPLETED=20} | 20 | 0 | 7.7 s | agrees |
| S3 | 20 | {COMPLETED=20} | 20 | 0 | 57.2 s | agrees |
| S4 | 20 | {COMPLETED=20} | 20 | 0 | 5.7 s | agrees |
| S5 | 20 | {COMPLETED=20} | 20 | 0 | 5.0 s | differs: no re-quote (complete-charges-held-quote) |
| S6 | 20 | {FAILED_NOT_CHARGED=20} | 0 | 0 | 5.6 s | agrees (refusal shape differs: refusal-4xx-at-complete) |
| S7 | 20 | {FAILED_NOT_CHARGED=20} | 0 | 0 | 5.5 s | agrees (refusal shape differs: refusal-4xx-at-complete) |
| S8 | 20 | {COMPLETED=20} | 20 | 0 | 110.3 s | agrees |
| S13 | 20 | {COMPLETED=20} | 20 | 0 | 4.8 s | agrees |
| S14 | 20 | {COMPLETED=20} | 20 | 0 | 5.8 s | agrees |

Totals: 220 runs, 180 orders, 0 violations.

## Wire differences

330 raw wire differences, 330 explained by 12 entries, 0 unclassified.

| entry | class | evidence | raw differences it explains | why |
| --- | --- | --- | --- | --- |
| error-codes-upper-case | theirs | computed | 2 | The sample writes codes in upper case (OUT_OF_STOCK, INSUFFICIENT_FUNDS); the lab uses lower case. The agent reads them case-insensitively. |
| complete-charges-held-quote | ambiguity | computed | 12 | After a price change in the sample's products table, a GET does not show it and complete charges the quote it holds; only an update re-prices, so the lab's re-quote steps (update, a second complete) have no counterpart. Neither the 2026-04-08 nor the 2026-08-25 text of Complete Checkout says whether a business must re-price (read 2026-11-14). |
| refusal-4xx-at-complete | choice | computed | 68 | The sample answers a refused complete (409 OUT_OF_STOCK, 402 INSUFFICIENT_FUNDS) as a 4xx with a UCP error envelope; the lab answers 200 with a message. The lab's answer is a whole checkout with a message; the sample's is an error envelope with no checkout body, so every checkout field of the lab's answer is absent from the sample's. Both shapes are legal and the agent accepts both. |
| fulfillment-mandatory | choice | computed | 102 | The sample needs a shipping method, destination and option (three full-replacement updates) before ready_for_complete; the lab needs none. The agent supports this through a FulfillmentPolicy and sends no address. |
| endpoint-at-origin | theirs | computed | 6 | The sample serves its REST endpoint at the origin; the lab's is under /ucp/v1. The proxy rule paths follow the profile's endpoint. |
| no-catalog-capability | theirs | computed | 8 | The sample declares no catalog capability, so the rows are orchestrator-direct and the agent cannot search it. |
| optional-links-and-continue-url | choice | computed | 24 | The lab's checkout carries a terms-of-service link and a continue_url; the sample returns an empty links array and no continue_url. Both are optional content under the checkout schema, so each merchant chooses. |
| sample-discount-capability | theirs | computed | 6 | The sample declares the discount capability and returns a discounts object; the agent ignores it. |
| payment-handler-listing | theirs | computed | 40 | Each merchant lists its own payment handlers in its profile and in every response envelope (the sample: google_pay, mock_payment_handler, shop_pay; the lab: mock_pay_1). The agent needs only its own handler id, checked at discovery. |
| response-envelope-status | theirs | computed | 6 | The sample's 2026-04-08 response envelope carries ucp.status; the lab's does not. The agent ignores it. |
| sample-signing-keys | theirs | computed | 36 | The sample publishes signing keys in its profile (signatures are optional and unused until H5). |
| sample-wider-capabilities | theirs | computed | 20 | The sample declares buyer_consent, cart, discount, fulfillment and order next to checkout; the lab declares checkout (and catalog). Fulfillment is the one that changes the agent's behaviour (fulfillment-mandatory). |
| profile-version-2026-04-08 | theirs | history | 0 | The sample's profile declares 2026-04-08 and refuses version="2026-08-25" with 422 (fixture 35). The agent pins ucp_versions per merchant. |
| agent-profile-fetch | theirs | probe | 0 | The sample fetches the URL in UCP-Agent on every create and update (4 per run); a run with the profile unreachable still completes. |
| idempotency-conflict-409 | theirs | probe | 0 | The same key with a different body is 409 IDEMPOTENCY_CONFLICT and the same key and body replays 200: correct behaviour, not a finding against the agent. |
| required-headers-422 | theirs | probe | 0 | A create without Request-Id, UCP-Agent or Idempotency-Key is 422 INVALID_REQUEST; the lab answers 400. |
| session-protection-hides-key-bug | theirs | history | 0 | A complete under a fresh key for a completed checkout makes no second order at the sample, so a planted "fresh key per complete" bug does not fail S13 there; the strict lab merchant catches it. |
| redactor-dropped-fulfillment | ours | history | 0 | The gateway's scrubbing rebuilt the checkout view without its fulfillment part, so the selection never progressed (found by the first live row). |
| payment-handler-constant | ours | history | 0 | UcpHttpGateway required the lab's handler id as a constant; the sample has another. |

### Every raw difference and the entry that explains it

- `S0/complete/path:/ucp/v1/checkout-sessions/{id}/complete|/checkout-sessions/{id}/complete` : endpoint-at-origin
- `S0/complete/field-only-lab:resp:continue_url` : optional-links-and-continue-url
- `S0/complete/field-only-sample:resp:discounts` : sample-discount-capability
- `S0/complete/field-only-sample:resp:fulfillment` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[]` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].destinations` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].destinations[]` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].destinations[].address_country` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].destinations[].address_locality` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].destinations[].address_region` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].destinations[].id` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].destinations[].postal_code` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].destinations[].street_address` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[]` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].id` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].line_item_ids` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].line_item_ids[]` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].options` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[]` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].id` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].title` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].totals` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].totals[]` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].totals[].amount` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].totals[].type` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].groups[].selected_option_id` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].id` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].line_item_ids` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].line_item_ids[]` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].selected_destination_id` : fulfillment-mandatory
- `S0/complete/field-only-sample:resp:fulfillment.methods[].type` : fulfillment-mandatory
- `S0/complete/field-only-lab:resp:links[]` : optional-links-and-continue-url
- `S0/complete/field-only-lab:resp:links[].type` : optional-links-and-continue-url
- `S0/complete/field-only-lab:resp:links[].url` : optional-links-and-continue-url
- `S0/complete/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S0/complete/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S0/complete/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S0/complete/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S0/complete/field-only-sample:resp:ucp.status` : response-envelope-status
- `S0/create/path:/ucp/v1/checkout-sessions|/checkout-sessions` : endpoint-at-origin
- `S0/create/field-only-lab:resp:continue_url` : optional-links-and-continue-url
- `S0/create/field-only-sample:resp:discounts` : sample-discount-capability
- `S0/create/field-only-lab:resp:links[]` : optional-links-and-continue-url
- `S0/create/field-only-lab:resp:links[].type` : optional-links-and-continue-url
- `S0/create/field-only-lab:resp:links[].url` : optional-links-and-continue-url
- `S0/create/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S0/create/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S0/create/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S0/create/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S0/create/field-only-sample:resp:ucp.status` : response-envelope-status
- `S0/discover/field-only-sample:resp:signing_keys` : sample-signing-keys
- `S0/discover/field-only-sample:resp:signing_keys[]` : sample-signing-keys
- `S0/discover/field-only-sample:resp:signing_keys[].alg` : sample-signing-keys
- `S0/discover/field-only-sample:resp:signing_keys[].crv` : sample-signing-keys
- `S0/discover/field-only-sample:resp:signing_keys[].kid` : sample-signing-keys
- `S0/discover/field-only-sample:resp:signing_keys[].kty` : sample-signing-keys
- `S0/discover/field-only-sample:resp:signing_keys[].use` : sample-signing-keys
- `S0/discover/field-only-sample:resp:signing_keys[].x` : sample-signing-keys
- `S0/discover/field-only-sample:resp:signing_keys[].y` : sample-signing-keys
- `S0/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.buyer_consent` : sample-wider-capabilities
- `S0/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.cart` : sample-wider-capabilities
- `S0/discover/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.catalog.lookup` : no-catalog-capability
- `S0/discover/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.catalog.search` : no-catalog-capability
- `S0/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.discount` : sample-wider-capabilities
- `S0/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.fulfillment` : sample-wider-capabilities
- `S0/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.order` : sample-wider-capabilities
- `S0/discover/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S0/discover/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S0/discover/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S0/discover/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S0/update/step-only-sample:` : fulfillment-mandatory
- `S0/update#2/step-only-sample:` : fulfillment-mandatory
- `S0/update#3/step-only-sample:` : fulfillment-mandatory
- `S5/complete/path:/ucp/v1/checkout-sessions/{id}/complete|/checkout-sessions/{id}/complete` : endpoint-at-origin
- `S5/complete/field-only-lab:resp:continue_url` : optional-links-and-continue-url
- `S5/complete/field-only-sample:resp:discounts` : sample-discount-capability
- `S5/complete/field-only-sample:resp:fulfillment` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[]` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].destinations` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].destinations[]` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].destinations[].address_country` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].destinations[].address_locality` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].destinations[].address_region` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].destinations[].id` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].destinations[].postal_code` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].destinations[].street_address` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[]` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].id` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].line_item_ids` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].line_item_ids[]` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].options` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[]` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].id` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].title` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].totals` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].totals[]` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].totals[].amount` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].options[].totals[].type` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].groups[].selected_option_id` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].id` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].line_item_ids` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].line_item_ids[]` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].selected_destination_id` : fulfillment-mandatory
- `S5/complete/field-only-sample:resp:fulfillment.methods[].type` : fulfillment-mandatory
- `S5/complete/field-only-lab:resp:links[]` : optional-links-and-continue-url
- `S5/complete/field-only-lab:resp:links[].type` : optional-links-and-continue-url
- `S5/complete/field-only-lab:resp:links[].url` : optional-links-and-continue-url
- `S5/complete/field-only-lab:resp:messages` : complete-charges-held-quote
- `S5/complete/field-only-lab:resp:messages[]` : complete-charges-held-quote
- `S5/complete/field-only-lab:resp:messages[].code` : complete-charges-held-quote
- `S5/complete/field-only-lab:resp:messages[].content` : complete-charges-held-quote
- `S5/complete/field-only-lab:resp:messages[].path` : complete-charges-held-quote
- `S5/complete/field-only-lab:resp:messages[].type` : complete-charges-held-quote
- `S5/complete/field-only-sample:resp:order` : complete-charges-held-quote
- `S5/complete/field-only-sample:resp:order.id` : complete-charges-held-quote
- `S5/complete/field-only-sample:resp:order.permalink_url` : complete-charges-held-quote
- `S5/complete/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S5/complete/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S5/complete/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S5/complete/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S5/complete/field-only-sample:resp:ucp.status` : response-envelope-status
- `S5/complete#2/step-only-lab:` : complete-charges-held-quote
- `S5/create/path:/ucp/v1/checkout-sessions|/checkout-sessions` : endpoint-at-origin
- `S5/create/field-only-lab:resp:continue_url` : optional-links-and-continue-url
- `S5/create/field-only-sample:resp:discounts` : sample-discount-capability
- `S5/create/field-only-lab:resp:links[]` : optional-links-and-continue-url
- `S5/create/field-only-lab:resp:links[].type` : optional-links-and-continue-url
- `S5/create/field-only-lab:resp:links[].url` : optional-links-and-continue-url
- `S5/create/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S5/create/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S5/create/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S5/create/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S5/create/field-only-sample:resp:ucp.status` : response-envelope-status
- `S5/discover/field-only-sample:resp:signing_keys` : sample-signing-keys
- `S5/discover/field-only-sample:resp:signing_keys[]` : sample-signing-keys
- `S5/discover/field-only-sample:resp:signing_keys[].alg` : sample-signing-keys
- `S5/discover/field-only-sample:resp:signing_keys[].crv` : sample-signing-keys
- `S5/discover/field-only-sample:resp:signing_keys[].kid` : sample-signing-keys
- `S5/discover/field-only-sample:resp:signing_keys[].kty` : sample-signing-keys
- `S5/discover/field-only-sample:resp:signing_keys[].use` : sample-signing-keys
- `S5/discover/field-only-sample:resp:signing_keys[].x` : sample-signing-keys
- `S5/discover/field-only-sample:resp:signing_keys[].y` : sample-signing-keys
- `S5/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.buyer_consent` : sample-wider-capabilities
- `S5/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.cart` : sample-wider-capabilities
- `S5/discover/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.catalog.lookup` : no-catalog-capability
- `S5/discover/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.catalog.search` : no-catalog-capability
- `S5/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.discount` : sample-wider-capabilities
- `S5/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.fulfillment` : sample-wider-capabilities
- `S5/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.order` : sample-wider-capabilities
- `S5/discover/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S5/discover/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S5/discover/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S5/discover/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S5/update/path:/ucp/v1/checkout-sessions/{id}|/checkout-sessions/{id}` : fulfillment-mandatory
- `S5/update/field-only-sample:req:fulfillment` : fulfillment-mandatory
- `S5/update/field-only-sample:req:fulfillment.methods` : fulfillment-mandatory
- `S5/update/field-only-sample:req:fulfillment.methods[]` : fulfillment-mandatory
- `S5/update/field-only-sample:req:fulfillment.methods[].id` : fulfillment-mandatory
- `S5/update/field-only-sample:req:fulfillment.methods[].line_item_ids` : fulfillment-mandatory
- `S5/update/field-only-sample:req:fulfillment.methods[].line_item_ids[]` : fulfillment-mandatory
- `S5/update/field-only-sample:req:fulfillment.methods[].type` : fulfillment-mandatory
- `S5/update/field-only-lab:resp:continue_url` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:discounts` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[]` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].destinations` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].destinations[]` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].destinations[].address_country` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].destinations[].address_locality` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].destinations[].address_region` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].destinations[].id` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].destinations[].postal_code` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].destinations[].street_address` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].id` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].line_item_ids` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].line_item_ids[]` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:fulfillment.methods[].type` : fulfillment-mandatory
- `S5/update/field-only-lab:resp:links[]` : fulfillment-mandatory
- `S5/update/field-only-lab:resp:links[].type` : fulfillment-mandatory
- `S5/update/field-only-lab:resp:links[].url` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : fulfillment-mandatory
- `S5/update/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : fulfillment-mandatory
- `S5/update/field-only-sample:resp:ucp.status` : fulfillment-mandatory
- `S5/update#2/step-only-sample:` : complete-charges-held-quote
- `S5/update#3/step-only-sample:` : complete-charges-held-quote
- `S6/complete/status:200|409` : refusal-4xx-at-complete
- `S6/complete/path:/ucp/v1/checkout-sessions/{id}/complete|/checkout-sessions/{id}/complete` : refusal-4xx-at-complete
- `S6/complete/error-code:out_of_stock|OUT_OF_STOCK` : error-codes-upper-case
- `S6/complete/field-only-lab:resp:buyer` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:buyer.email` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:continue_url` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:currency` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:id` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[]` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].id` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].item` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].item.id` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].item.price` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].item.title` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].quantity` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].totals` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].totals[]` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].totals[].amount` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:line_items[].totals[].type` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:links` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:links[]` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:links[].type` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:links[].url` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:messages[].path` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:status` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:totals` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:totals[]` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:totals[].amount` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:totals[].type` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:ucp.capabilities` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.checkout` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:ucp.payment_handlers` : refusal-4xx-at-complete
- `S6/complete/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : refusal-4xx-at-complete
- `S6/complete/field-only-sample:resp:ucp.status` : refusal-4xx-at-complete
- `S6/create/path:/ucp/v1/checkout-sessions|/checkout-sessions` : endpoint-at-origin
- `S6/create/field-only-lab:resp:continue_url` : optional-links-and-continue-url
- `S6/create/field-only-sample:resp:discounts` : sample-discount-capability
- `S6/create/field-only-lab:resp:links[]` : optional-links-and-continue-url
- `S6/create/field-only-lab:resp:links[].type` : optional-links-and-continue-url
- `S6/create/field-only-lab:resp:links[].url` : optional-links-and-continue-url
- `S6/create/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S6/create/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S6/create/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S6/create/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S6/create/field-only-sample:resp:ucp.status` : response-envelope-status
- `S6/discover/field-only-sample:resp:signing_keys` : sample-signing-keys
- `S6/discover/field-only-sample:resp:signing_keys[]` : sample-signing-keys
- `S6/discover/field-only-sample:resp:signing_keys[].alg` : sample-signing-keys
- `S6/discover/field-only-sample:resp:signing_keys[].crv` : sample-signing-keys
- `S6/discover/field-only-sample:resp:signing_keys[].kid` : sample-signing-keys
- `S6/discover/field-only-sample:resp:signing_keys[].kty` : sample-signing-keys
- `S6/discover/field-only-sample:resp:signing_keys[].use` : sample-signing-keys
- `S6/discover/field-only-sample:resp:signing_keys[].x` : sample-signing-keys
- `S6/discover/field-only-sample:resp:signing_keys[].y` : sample-signing-keys
- `S6/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.buyer_consent` : sample-wider-capabilities
- `S6/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.cart` : sample-wider-capabilities
- `S6/discover/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.catalog.lookup` : no-catalog-capability
- `S6/discover/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.catalog.search` : no-catalog-capability
- `S6/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.discount` : sample-wider-capabilities
- `S6/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.fulfillment` : sample-wider-capabilities
- `S6/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.order` : sample-wider-capabilities
- `S6/discover/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S6/discover/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S6/discover/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S6/discover/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S6/update/step-only-sample:` : fulfillment-mandatory
- `S6/update#2/step-only-sample:` : fulfillment-mandatory
- `S6/update#3/step-only-sample:` : fulfillment-mandatory
- `S7/complete/status:200|402` : refusal-4xx-at-complete
- `S7/complete/path:/ucp/v1/checkout-sessions/{id}/complete|/checkout-sessions/{id}/complete` : refusal-4xx-at-complete
- `S7/complete/error-code:payment_declined|INSUFFICIENT_FUNDS` : error-codes-upper-case
- `S7/complete/field-only-lab:resp:buyer` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:buyer.email` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:continue_url` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:currency` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:id` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[]` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].id` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].item` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].item.id` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].item.price` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].item.title` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].quantity` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].totals` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].totals[]` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].totals[].amount` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:line_items[].totals[].type` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:links` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:links[]` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:links[].type` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:links[].url` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:messages[].path` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:status` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:totals` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:totals[]` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:totals[].amount` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:totals[].type` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:ucp.capabilities` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.checkout` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:ucp.payment_handlers` : refusal-4xx-at-complete
- `S7/complete/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : refusal-4xx-at-complete
- `S7/complete/field-only-sample:resp:ucp.status` : refusal-4xx-at-complete
- `S7/create/path:/ucp/v1/checkout-sessions|/checkout-sessions` : endpoint-at-origin
- `S7/create/field-only-lab:resp:continue_url` : optional-links-and-continue-url
- `S7/create/field-only-sample:resp:discounts` : sample-discount-capability
- `S7/create/field-only-lab:resp:links[]` : optional-links-and-continue-url
- `S7/create/field-only-lab:resp:links[].type` : optional-links-and-continue-url
- `S7/create/field-only-lab:resp:links[].url` : optional-links-and-continue-url
- `S7/create/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S7/create/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S7/create/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S7/create/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S7/create/field-only-sample:resp:ucp.status` : response-envelope-status
- `S7/discover/field-only-sample:resp:signing_keys` : sample-signing-keys
- `S7/discover/field-only-sample:resp:signing_keys[]` : sample-signing-keys
- `S7/discover/field-only-sample:resp:signing_keys[].alg` : sample-signing-keys
- `S7/discover/field-only-sample:resp:signing_keys[].crv` : sample-signing-keys
- `S7/discover/field-only-sample:resp:signing_keys[].kid` : sample-signing-keys
- `S7/discover/field-only-sample:resp:signing_keys[].kty` : sample-signing-keys
- `S7/discover/field-only-sample:resp:signing_keys[].use` : sample-signing-keys
- `S7/discover/field-only-sample:resp:signing_keys[].x` : sample-signing-keys
- `S7/discover/field-only-sample:resp:signing_keys[].y` : sample-signing-keys
- `S7/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.buyer_consent` : sample-wider-capabilities
- `S7/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.cart` : sample-wider-capabilities
- `S7/discover/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.catalog.lookup` : no-catalog-capability
- `S7/discover/field-only-lab:resp:ucp.capabilities.dev.ucp.shopping.catalog.search` : no-catalog-capability
- `S7/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.discount` : sample-wider-capabilities
- `S7/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.fulfillment` : sample-wider-capabilities
- `S7/discover/field-only-sample:resp:ucp.capabilities.dev.ucp.shopping.order` : sample-wider-capabilities
- `S7/discover/field-only-sample:resp:ucp.payment_handlers.com.google.pay` : payment-handler-listing
- `S7/discover/field-only-sample:resp:ucp.payment_handlers.dev.mock.payment_handler` : payment-handler-listing
- `S7/discover/field-only-sample:resp:ucp.payment_handlers.dev.shopify.shop_pay` : payment-handler-listing
- `S7/discover/field-only-lab:resp:ucp.payment_handlers.dev.ucplab.mock_pay` : payment-handler-listing
- `S7/update/step-only-sample:` : fulfillment-mandatory
- `S7/update#2/step-only-sample:` : fulfillment-mandatory
- `S7/update#3/step-only-sample:` : fulfillment-mandatory

## Probes

- S17 first complete: 200 completed; same key and same body: 200 completed; same key and a DIFFERENT body: 409 {"ucp":{"version":"2026-04-08","status":"error"},"messages":[{"type":"error","code":"IDEMPOTENCY_CONFLICT","content":"Idempotency key reused with different parameters","severity":"unrecoverable"}]}; orders created by the three: 1
- create without Request-Id: 422 {"ucp":{"version":"2026-04-08","status":"error"},"messages":[{"type":"error","code":"INVALID_REQUEST","content":"✖ Field required\n  → at request-id","severity":"unrecoverable"}]}
- create without UCP-Agent: 422 {"ucp":{"version":"2026-04-08","status":"error"},"messages":[{"type":"error","code":"INVALID_REQUEST","content":"✖ Field required\n  → at ucp-agent","severity":"unrecoverable"}]}
- create without Idempotency-Key: 422 {"ucp":{"version":"2026-04-08","status":"error"},"messages":[{"type":"error","code":"INVALID_REQUEST","content":"✖ Field required\n  → at idempotency-key","severity":"unrecoverable"}]}
- unreachable agent profile URL: the agent's run ended COMPLETED, new orders 1, violations []
