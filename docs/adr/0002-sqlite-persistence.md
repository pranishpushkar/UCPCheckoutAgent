# ADR 0002: SQLite, plain JDBC and Flyway in agent-persistence

Status: accepted (M3). Sections 10 and 15 of the design fix the choice; this records the versions, the settings and
what was checked.

## Decision

- Plain JDBC (`java.sql`), no ORM. `org.xerial:sqlite-jdbc` 3.54.1.0 and `org.flywaydb:flyway-core` 13.12.1, pinned in
  the parent pom. The Postgres driver is not added until Postgres is (M7, 21.3).
- Tests run on a temp file per test class (`@TempDir`), never `:memory:`: an in-memory database cannot be reopened, and
  S9 needs a second process-like start over the same file. No external service is needed.
- Every connection is opened through `SQLiteConfig` with: `journal_mode=WAL`, `busy_timeout=5000`,
  `foreign_keys=ON`, and `transaction_mode=IMMEDIATE`.
- SQL stays in the portable subset (section 10). A lint test over the migrations arrives with the first migration.

## Why IMMEDIATE

Under WAL a deferred transaction that reads and then writes can fail at the upgrade with `SQLITE_BUSY_SNAPSHOT`, and
`busy_timeout` cannot help it. `BEGIN IMMEDIATE` takes the write lock at the start, so concurrent writers queue behind
`busy_timeout` and then fail with a plain `SQLITE_BUSY`. Every transaction here is one repository method, so none is
long. `SqliteSetupTest` proves that a second writer is refused at `BEGIN`.

## Checked from the published artifacts (2026-11-12)

| Item | Source | Result |
| --- | --- | --- |
| Flyway licence | `flyway-core-13.12.1.pom` and `flyway-parent-13.12.1.pom` | Both declare Apache License 2.0. The pom's licence URL points at the project README, not the licence text, so the declaration is the pom's own |
| sqlite-jdbc licence | `sqlite-jdbc-3.54.1.0.pom` | Apache License 2.0 |
| SQLite support in Flyway | `flyway-core-13.12.1.jar` | `org.flywaydb.core.internal.database.sqlite` is in the core jar; no extra module |
| BEGIN IMMEDIATE | `sqlite-jdbc-3.54.1.0.jar`, `javap` | `SQLiteConfig.setTransactionMode(TransactionMode.IMMEDIATE)` exists (also `DEFERRED`, `EXCLUSIVE`), as do `setBusyTimeout`, `setJournalMode`, `enforceForeignKeys` |

Not checked: that Flyway 13 supports JDK 21 officially. The test run on JDK 21 is the evidence.

## Consequences

- The driver is native code (JNI). Calls into it pin a virtual thread's carrier, so recovery drivers run on platform
  threads (7.8). The driver synchronises internally; our code adds no `synchronized` and no lock around JDBC.
- `agent-core` publishes a test-jar so the contract suites and the crash matrix run against SQL too.
