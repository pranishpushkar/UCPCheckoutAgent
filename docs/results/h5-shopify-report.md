DRAFT

# H5: one live run at a Shopify development store

Every number and result below is a placeholder, filled in from the Stage 3 run (`scripts/shopify-live.sh`,
[shopify-live.md](../interop/shopify-live.md)).

## What ran

- Date: `<date>`. Commit: `<hash>`. Model: the scripted model (`AGENT_MODEL=scripted`).
- The store: the owner's development store, reached over MCP (`transport: mcp`, `completion: handoff`, `owned: true`,
  `requires_buyer_ip: true`), at UCP `<version>`.
- Query: `<query>`. Item: `<variant>`, quantity `<n>`, unit price `<price>`.

## The path, end to end

| Step | Result |
| --- | --- |
| Search at the store | `<n>` products |
| Quote (`create_checkout`) | status `<status>`, total `<total>` |
| Approval cards shown | `<n>` |
| Re-quotes (`requote_count`) | `<n>` |
| `get_checkout` at hand_off | `<same as approved / changed>` |
| Final state | `<HANDED_OFF / REJECTED price_unstable / ...>` |
| Session cancelled after the owner saw the page (VERIFY 5) | `<status>` |

## What the buyer saw

`<the store's checkout page: what it showed, what it asked for; no URL, no screenshot with a key>`

## get_checkout against create_checkout

`<the comparison table from notes/research/stage3-get-vs-create.md: currency, subtotal, tax, shipping, discounts and
total across create, both GETs and the update; whether a difference is systematic and how large>`

## The leak scan

`scripts/h5-leak-scan.sh`: `<PASSED / FAILED>`, over the run's evidence and recordings and the branch
`develop..HEAD`. `<counts per pattern>`.

## What is not covered

- N1: no `complete` at Shopify; the buyer pays at the store.
- Order reads (VERIFY 7).
- The per-user buyer IP: `BUYER_IP` is the owner's own address, `local` profile only (19).

## The VERIFY answers

| # | Question | Answer |
| --- | --- | --- |
| 5 | `continue_url`'s form and lifetime; cancel of a handed-off checkout | `<answer>` |
| 8 | A business refusal over MCP | `<answer>` |
| 9 | The arguments of `get_checkout` and `update_checkout` | `<answer>` |
| 10 | Whether the MCP endpoint differs between versions | `<answer>` |
