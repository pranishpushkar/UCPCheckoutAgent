# Follow-ups (production work for later)

Work deliberately left for later. Each item keeps the condition it must meet before it lands; a condition here is the
owner's, and an item is done only when its condition is met.

## SQL statement cache (from the CI-speed profiling, 2026-11-19)

- **What:** the SQL repositories call `prepareStatement` on every call; nothing caches compiled statements.
- **Evidence:** a JFR profile of `SqlHandoffPropertyTest` at 100 tries (develop, local) put about 28% of the test thread's native SQLite time in `NativeDB.prepare`. COMMIT was another 30–35%.
- **Why later:** it changes production code in `agent-persistence`. The CI-speed work was limited to setup changes (the owner chose tmpfs for test databases in CI).
- **Care needed:** connections are pooled (`Database.open(file, 2)`), and a cache must be per connection and closed with it. It mustn't hold a connection across network I/O (CLAUDE.md), and caching statements isn't caching reads (section 20).
- **Blocks it (owner, 2026-11-19):** equivalents 83, 84, 90 and 91 (`docs/mutation/equivalents.md`) hold only while every PreparedStatement is fresh and the driver is sqlite-jdbc: an unset parameter is then bound as NULL. A cached statement keeps the previous call's binding, so each of them becomes a real bug and a survivor. Revisit and kill all four (bind every parameter explicitly, with a test that reuses a statement) before the cache lands. The same applies to the move to Postgres (M7), since pgjdbc refuses an unset parameter.

## Daily model-call cap (from the #28 live check, 2026-11-19)

- **What:** a daily cap on live model calls (or tokens), one per user and one global, checked before each model call
  and refused with a fixed reason once reached. DESIGN's deployment table already plans it ("live Gemini behind a
  per-user and a global daily cap"); nothing implements it yet.
- **Today:** the only model limits are per run (6.3: 6 model calls, 8 tool calls, 30 s, 20,000 tokens). P3's daily cap
  limits purchases, not the model. A console run with `--model gemini` is the owner's alone, on the owner's key, for
  as long as the owner keeps it open (26.6).
- **Condition (owner, 2026-11-19):** before the demo is opened to anyone besides the owner, with the live model or
  any shared key. Until then the scripted model stays the default for everyone else.
- **Care needed:** the count is per user from AuthContext only (section 18) and global across the service's workers;
  it is stored, not cached (section 20), and a refused call has no side effects, like a run budget (6.3).

## Deal comparison (from the #34 live re-check from India, 2026-11-20)

- **What:** "Deal comparison: compare listed prices across offers for the same product and size (catalog UPID cluster) in code, show cheaper offers or up to 3 alternatives on the card, and say 'lowest listed price among N offers; shipping, tax and duties are set at each store's checkout.' Strongest first step: the same-item cheapest-offer check." (owner, 2026-11-20)
