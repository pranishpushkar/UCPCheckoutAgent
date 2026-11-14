# Project rules
- The design is docs/DESIGN.md. Sections marked [CONTRACT] are normative: implement names,
  states, signatures and invariants exactly. Never change a contract to make a test pass.
- If something is marked VERIFY, check the linked source. If it disagrees, write a failing
  test and a TODO(VERIFY) comment. Do not guess UCP or ADK APIs.
- Java 21, Maven multi-module, package root dev.ucplab. Records for DTOs, sealed interfaces
  for outcomes, no Lombok, no null returns, inject Clock and IdGenerator.
- agent-core depends on the JDK only. Only agent-adk may import ADK or Gemini SDK types.
- Never block inside synchronized. Never hold a lock across network I/O.
- Don't modify merchant/src except the M2 catalog addition and the M6a `--catalog` option; never change the original 46 SelfTest checks.
- Never log credentials, tokens, Authorization headers or approval tokens.
- Every test cites the IDs it covers (FR, I, S) in a comment.
- User identity comes only from AuthContext. Every repository call on intents, approvals
  or runs takes the owner's UserId (section 18). Never trust a user id from a request body.
- Never cache checkout sessions, quotes, approvals or reconciliation reads (section 20).
- Work one milestone at a time (section 16). A milestone is done only when its acceptance
  criteria pass under ./mvnw verify.
- Never add Co-Authored-By, Claude-Session or "Generated with Claude Code" lines to commits or PRs.
- Don't add jqwik or any other property-testing library. Property tests follow section 14.1 of docs/DESIGN.md.
- Third-party merchants and their tooling (the UCP samples, `uv`, Python) run only through `scripts/interop-*` and `-Pinterop`, from a clone pinned to a commit hash recorded in `docs/interop/pin.txt` with a locked, hashed dependency set. They never run in `./mvnw verify`, and no job that runs them has a secret.
- Nothing in verify needs Python, `uv`, Docker or the network.
- A secret for a real service (a Shopify key) lives in the Keychain and the environment only: never in a file, an image, an argument or a log; scripts that use one have a `--dry-run` that shows every call with the secret replaced.
