# Project rules
- The design is docs/DESIGN.md. Sections marked [CONTRACT] are normative: implement names,
  states, signatures and invariants exactly. Never change a contract to make a test pass.
- If something is marked VERIFY, check the linked source. If it disagrees, write a failing
  test and a TODO(VERIFY) comment. Do not guess UCP or ADK APIs.
- Java 21, Maven multi-module, package root dev.ucplab. Records for DTOs, sealed interfaces
  for outcomes, no Lombok, no null returns, inject Clock and IdGenerator.
- agent-core depends on the JDK only. Only agent-adk may import ADK types.
- Never block inside synchronized. Never hold a lock across network I/O.
- The merchant module is the system under test. Do not edit it to make agent tests pass.
- Never log credentials, tokens, Authorization headers or approval tokens.
- Every test cites the IDs it covers (FR, I, S) in a comment.
- User identity comes only from AuthContext. Every repository call on intents, approvals
  or runs takes the owner's UserId (section 18). Never trust a user id from a request body.
- Never cache checkout sessions, quotes, approvals or reconciliation reads (section 20).
- Work one milestone at a time (section 16). A milestone is done only when its acceptance
  criteria pass under ./mvnw verify.
