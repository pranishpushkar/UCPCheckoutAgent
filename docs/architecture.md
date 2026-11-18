# Architecture (as H5 built it)

The stack of `docker compose up`: eight containers sharing one network namespace. The merchants and the fault proxies
listen on loopback only (the merchant's source is frozen apart from its catalog option; the proxy's contract says
127.0.0.1), so `lab-net` owns the namespace and the other seven join it. Only `127.0.0.1:8080` is published, for the
console and the REST API. There are three merchants, each with a proxy of its own (one proxy has one upstream). The
merchant image is one image: `merchant-b` and `merchant-c` pass `--catalog` with a file from `dev/catalogs/`. The dashed
box is optional (Gemini). The registry reads `merchants.yaml`; a merchant that fails its discovery check starts SUSPENDED
and is left out of search until a check passes again.

The Shopify store is not part of the Compose stack. The H5 live run (`scripts/shopify-live.sh`) starts the service with
`java -jar` and a temporary `merchants.yaml` that lists the owner's development store alone, reached over MCP.

```mermaid
flowchart LR
  subgraph host["Your machine"]
    browser["Browser<br/>lab console"]
    cli["CLI<br/>scripts/ucplab"]
  end
  subgraph ns["lab-net: one network namespace, 127.0.0.1:8080 published"]
    subgraph svc["agent-service (Spring Boot)"]
      api["REST API and console<br/>session, CSRF, no-store"]
      rt["Agent runtime<br/>5 tools, budgets, cannot pay"]
      orch["Orchestrator<br/>approvals, write-ahead attempts,<br/>handoff, recovery worker"]
      gw["Merchant gateways<br/>UCP over HTTP, UCP over MCP<br/>breaker and rate limit per merchant"]
      reg["Merchant registry<br/>merchants.yaml, discovery check"]
      db[("SQLite<br/>volume ucplab-data")]
    end
    proxies["Fault proxies a, b, c<br/>drop, delay, duplicate"]
    merchants["Lab merchants a, b, c<br/>UCP checkout, order counter"]
  end
  shopify(["Shopify development store<br/>UCP over MCP, the H5 live run"])
  gemini(["Gemini<br/>only if AGENT_MODEL=gemini"])

  browser -->|"HTTP, cookie and CSRF"| api
  cli -->|"HTTP, same API"| api
  api --> rt
  rt -->|"search every merchant"| gw
  rt -->|"propose only"| orch
  api -->|"approve"| orch
  orch --> db
  orch --> gw
  reg -.->|"discovery"| gw
  gw -->|"UCP over HTTP"| proxies --> merchants
  gw -->|"UCP over MCP, owned store only"| shopify
  browser -.->|"handoff: the buyer pays at the store"| shopify
  api -->|"lab controls, local only"| proxies
  rt -.->|"model calls"| gemini
  classDef ext stroke-dasharray: 5 5
  class gemini ext
```

The agent can only propose a purchase. Approval comes from the signed-in person through the REST API, and the
orchestrator alone calls `complete`, with a deterministic idempotency key, from a request body it wrote down first.

## The lost-response path (DESIGN 5.4)

The case this project exists to test: the merchant creates the order and the response never arrives.

```mermaid
sequenceDiagram
  participant O as Orchestrator
  participant P as Fault proxy
  participant M as Merchant
  O->>P: POST complete (key K)
  P->>M: forward
  M-->>P: 200 completed (order created)
  P--xO: response dropped
  O->>O: Uncertain, state RECONCILING (never a new session)
  O->>P: GET /checkout-sessions/{id}
  P->>M: forward
  M-->>O: status completed, order id
  O->>O: COMPLETED (exactly one order)
```
