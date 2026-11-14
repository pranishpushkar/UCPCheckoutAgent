# Architecture (as M6 built it)

The stack of `docker compose up`: four containers sharing one network namespace. The merchant and the fault proxy
listen on loopback only (the merchant's source is frozen; the proxy's contract says 127.0.0.1), so `lab-net` owns the
namespace and the other three join it. Only `127.0.0.1:8080` is published, for the console and the REST API.
Dashed boxes are what comes next.

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
      orch["Orchestrator<br/>approvals, write-ahead attempts"]
      rec["Recovery worker"]
      db[("SQLite<br/>volume ucplab-data")]
    end
    proxy["Fault proxy :9101<br/>admin API"]
    merchant["merchant-a :9001<br/>UCP checkout, /_lab"]
  end
  gemini(["Gemini<br/>only if AGENT_MODEL=gemini"])
  keycloak["Keycloak, OIDC<br/>M6b"]
  more["merchant-b and merchant-c<br/>M6a"]
  pg[("Postgres, several instances<br/>M7")]

  browser -->|HTTP, cookie and CSRF| api
  cli -->|HTTP, same API| api
  api --> sup --> rt
  rt -->|propose only| orch
  api -->|approve| orch
  orch --> db
  rec --> orch
  rt -.->|model calls| gemini
  orch -->|UCP over HTTP| proxy --> merchant
  api -->|lab controls, local only| proxy
  api -->|orders, reset, price| merchant
  keycloak -.-> api
  more -.-> proxy
  pg -.-> orch
  classDef next stroke-dasharray: 5 5
  class keycloak,more,pg,gemini next
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
