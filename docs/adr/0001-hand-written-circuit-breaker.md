# ADR 0001: A hand-written circuit breaker in agent-ucp

Status: accepted (M2). Section 15 of the design asks for this choice to be recorded.

## Context

The UCP client needs a circuit breaker per merchant (8.3, section 19): after repeated failures it stops sending new
calls to a host for a while, so a broken merchant is not hammered and one failing merchant never affects the others
(G7, S20). `agent-ucp` may depend only on `agent-core` and the JDK (5.2), and the design lists no resilience library in
the stack.

## Decision

A small class, `CircuitBreaker` (about 80 lines of logic), plus `CircuitBreakers`, a map of one breaker per key. It is
driven by the injected `Clock`, holds its state in memory, and uses a `ReentrantLock` that is never held across a call
(7.8). It plugs into `RetryExecutor` through a two-method `Gate` interface.

Rejected: Resilience4j or another library. It would add a dependency and its own clock and threading model to a module
that must stay small and deterministic under test, for a state machine of three states.

## What counts as a failure

- Failures: `NotSent`, `Uncertain`, and a `ProtocolError` with a 5xx status. This includes a 503, and a complete that
  the classifier turned into `Uncertain` after a 429 or 503.
- Not failures: `Ok`, `Rejected` (a business answer), a 4xx, a 429, and any business message. The host answered and is
  alive, so these reset the consecutive-failure count. A 429 is the host asking for slower traffic, which the retry
  layer handles with `Retry-After`; it is not evidence that the host is down.
- A call the breaker itself rejected is `NotSent` and is never recorded, or the breaker would count its own rejections.

## Behaviour

Opens after 5 consecutive failures, stays open 30 s, then admits exactly one probe. A successful probe closes it; a
failed one re-opens it for a full period. A probe whose result never arrives is replaced after one more period. A result
that arrives while the breaker is open (an attempt admitted before it opened) is ignored. All values are in `UcpConfig`.

## Keyed by host:port

The key is `host:port` of the merchant's checkout endpoint, not the merchant id and not the host alone. In the lab all
merchants run on localhost on different ports, so the host alone would make them share one breaker and a failing
merchant would stop its neighbours. Merchants behind one real host share that host's breaker, which is the intent: they
share the failure.

## Reconciliation reads bypass it

`get` is the only call the orchestrator uses to establish what happened after an uncertain `complete` (7.5). An open
breaker must not hide the answer, because the intent would then wait for the reconciliation deadline and escalate for
want of a read that is always safe. So the gateway marks `get` as bypassing: it neither asks the breaker nor records
into it. Calls that start something (create, update) are blocked, so a broken merchant gets no new quotes. `complete`
and `cancel` go through the breaker like any other call: an open breaker turns them into `NotSent`, which is true (nothing
was sent) and which the orchestrator already handles.

## Consequences

- Breakers are per process. That protects availability, not correctness (21.3): the idempotency keys and the write-ahead
  log keep correctness whatever the breaker does.
- A merchant that fails five times in a row blocks new quotes for 30 s even if it recovers sooner; the probe limits the
  cost of that.
- The failure set is a rule in one function, `isFailure`, covered by a test per outcome kind.
