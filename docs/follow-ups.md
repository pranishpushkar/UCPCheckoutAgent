# Follow-ups (production work for later)

Work deliberately left for later. Each item keeps the condition it must meet before it lands; a condition here is the
owner's, and an item is done only when its condition is met.

## SQL statement cache (from the CI-speed profiling, 2026-11-19)

- **What:** the SQL repositories call `prepareStatement` on every call; nothing caches compiled statements.
- **Evidence:** a JFR profile of `SqlHandoffPropertyTest` at 100 tries (develop, local) put about 28% of the test thread's native SQLite time in `NativeDB.prepare`. COMMIT was another 30–35%.
- **Why later:** it changes production code in `agent-persistence`. The CI-speed work was limited to setup changes (the owner chose tmpfs for test databases in CI).
- **Care needed:** connections are pooled (`Database.open(file, 2)`), and a cache must be per connection and closed with it. It mustn't hold a connection across network I/O (CLAUDE.md), and caching statements isn't caching reads (section 20).
- **Blocks it (owner, 2026-11-19):** equivalents 83, 84, 90 and 91 (`docs/mutation/equivalents.md`) hold only while every PreparedStatement is fresh and the driver is sqlite-jdbc: an unset parameter is then bound as NULL. A cached statement keeps the previous call's binding, so each of them becomes a real bug and a survivor. Revisit and kill all four (bind every parameter explicitly, with a test that reuses a statement) before the cache lands. The same applies to the move to Postgres (M7), since pgjdbc refuses an unset parameter.
