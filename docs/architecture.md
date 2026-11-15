# Architecture (as M6a built it)

The stack of `docker compose up`: eight containers sharing one network namespace. The merchants and the fault proxies
listen on loopback only (the merchant's source is frozen apart from its catalog option; the proxy's contract says
127.0.0.1), so `lab-net` owns the namespace and the other seven join it. Only `127.0.0.1:8080` is published, for the
console and the REST API. There are three merchants, each with a proxy of its own (one proxy has one upstream). The
merchant image is one image: `merchant-b` and `merchant-c` pass `--catalog` with a file from `dev/catalogs/`. Dashed boxes
are what comes next (M6b and M7). The registry reads `merchants.yaml`; a merchant that fails its discovery check starts
SUSPENDED and is left out of search until a check passes again.

```mermaid
flowchart LR
  subgraph host["Your machine"]
    browser["Browser<br/>lab console"]
    cli["CLI<br/>scripts/ucplab"]
  end
  subgraph ns["lab-net: one network namespace, 127.0.0.1:8080 published"]
    subgraph svc["agent-service (Spring Boot)"]
      api["REST API and console<br/>session, CSRF, no-store"]
      sup["Run supervisor<br/>one active run per user"]
      rt["Agent runtime<br/>5 tools, budgets"]
      reg["Merchant registry<br/>merchants.yaml, discovery check"]
      orch["Orchestrator<br/>approvals, write-ahead attempts<br/>gateway of the intent's merchant"]
      rec["Recovery worker"]
      db[("SQLite<br/>volume ucplab-data")]
    end
    proxyA["fault-proxy-a :9101<br/>admin API"]
    proxyB["fault-proxy-b :9102"]
    proxyC["fault-proxy-c :9103"]
    merchantA["merchant-a :9001<br/>UCP checkout, /_lab"]
    merchantB["merchant-b :9002"]
    merchantC["merchant-c :9003"]
  end
  gemini(["Gemini<br/>only if AGENT_MODEL=gemini"])
  keycloak["Keycloak, OIDC<br/>M6b"]
  pg[("Postgres, several instances<br/>M7")]

  browser -->|HTTP, cookie and CSRF| api
  cli -->|HTTP, same API| api
  api --> sup --> rt
  rt -->|propose only; search asks every merchant| orch
  api -->|approve| orch
  orch --> db
  rec --> orch
  reg -.->|discovery| proxyA
  rt -.->|model calls| gemini
  orch -->|UCP over HTTP| proxyA --> merchantA
  orch -->|UCP over HTTP| proxyB --> merchantB
  orch -->|UCP over HTTP| proxyC --> merchantC
  api -->|lab controls, local only| proxyA
  api -->|orders, reset, price| merchantA
  keycloak -.-> api
  pg -.-> orch
  classDef next stroke-dasharray: 5 5
  class keycloak,pg,gemini next
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
