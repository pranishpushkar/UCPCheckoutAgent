package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

    @TempDir
    Path dir;

    private static List<String> column(Database db, String sql) {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
            return out;
        });
    }

    // Covers: section 10 (V1 creates the intent and event tables and the dedupe index; migrating twice is a no-op)
    @Test
    void migrate_createsV1Schema_andIsIdempotent() {
        Path file = dir.resolve("m.db");
        Migrations.migrate(file);
        Migrations.migrate(file);

        try (Database db = Database.open(file, 1)) {
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'table'"))
                    .contains("purchase_intent", "intent_event");
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'index'"))
                    .contains("ux_intent_active_dedupe", "ix_intent_user");
            assertThat(column(db, "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank"))
                    .startsWith("1");
        }
    }

    // Covers: 7.7, 7.1 (the terminal states named in the partial unique index are exactly IntentState's terminal states,
    // so the SQL and the in-memory repository agree on what is active; V9 is the newest definition, with HANDED_OFF)
    @Test
    void dedupeIndex_namesExactlyTheTerminalStates() throws IOException {
        assertDedupeListMatchesEnum(Files.readString(Path.of("src/main/resources/db/migration/V9__intent_handoff_url.sql")));
    }

    private static void assertDedupeListMatchesEnum(String sql) {
        Matcher m = Pattern.compile("ux_intent_active_dedupe.*?NOT IN \\(([^)]*)\\)", Pattern.DOTALL).matcher(sql);

        assertThat(m.find()).isTrue();
        TreeSet<String> named = new TreeSet<>();
        for (String s : m.group(1).split(",")) {
            named.add(s.trim().replace("'", ""));
        }
        TreeSet<String> terminal = new TreeSet<>();
        for (IntentState s : IntentState.values()) {
            if (s.isTerminal()) {
                terminal.add(s.name());
            }
        }
        assertThat(named).isEqualTo(terminal);
    }

    // Covers: section 10 (the partial unique index lets a terminal intent and a new one share a dedupe key, and refuses
    // two active ones)
    @Test
    void dedupeIndex_isUniqueOnlyAmongActiveIntents() throws SQLException {
        Path file = dir.resolve("m.db");
        Migrations.migrate(file);
        try (Database db = Database.open(file, 1)) {
            String insert = "INSERT INTO purchase_intent (id, user_id, merchant_id, dedupe_key, lines_json, buyer_email,"
                    + " state, version, created_at, updated_at) VALUES ('%s', 'u', 'm', 'k', '[]', 'e', '%s', 0, 't', 't')";
            db.write(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute(String.format(insert, "a", "REJECTED"));
                    s.execute(String.format(insert, "b", "PROPOSED"));
                }
                return null;
            });

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.write(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute(String.format(insert, "c", "QUOTING"));
                }
                return null;
            })).isInstanceOf(DatabaseException.class);
        }
    }

    private static final String INSERT_INTENT = "INSERT INTO purchase_intent (id, user_id, merchant_id, dedupe_key,"
            + " lines_json, buyer_email, state, version, order_id, created_at, updated_at)"
            + " VALUES ('%s', 'u', '%s', '%s', '[]', 'e', '%s', 0, %s, 't', 't')";

    private static void exec(Database db, String... statements) {
        db.write(c -> {
            try (Statement s = c.createStatement()) {
                for (String sql : statements) {
                    s.execute(sql);
                }
            }
            return null;
        });
    }

    private static String intent(String id, String merchant, String key, String state, String orderId) {
        return String.format(INSERT_INTENT, id, merchant, key, state, orderId == null ? "NULL" : "'" + orderId + "'");
    }

    // Covers: section 10, 7.6 (V4 rebuilds the four tables: rows written under V3 survive, and the new guards then apply)
    @Test
    void v4_keepsRowsWrittenUnderV3() {
        Path file = dir.resolve("upgrade.db");
        Flyway.configure().dataSource("jdbc:sqlite:" + file, null, null).locations("classpath:db/migration")
                .target("3").load().migrate();
        try (Database old = Database.open(file, 1)) {
            exec(old, intent("i1", "m", "k1", "AWAITING_APPROVAL", null),
                    "INSERT INTO intent_event (intent_id, seq, type, payload_json, at) VALUES ('i1', 1, 't', '{}', 't')",
                    "INSERT INTO merchant_attempt (id, intent_id, seq, op, revision, status, sent_at)"
                            + " VALUES ('a1', 'i1', 1, 'CREATE', 0, 'SENT', 't')",
                    "INSERT INTO approval (id, intent_id, seq, user_id, snapshot_json, snapshot_hash, token_hash,"
                            + " expires_at, status) VALUES ('p1', 'i1', 1, 'u', '{}', 'h', 'th', 't', 'PENDING')");
        }

        Migrations.migrate(file);

        try (Database db = Database.open(file, 1)) {
            assertThat(column(db, "SELECT id FROM purchase_intent")).containsExactly("i1");
            assertThat(column(db, "SELECT type FROM intent_event")).containsExactly("t");
            assertThat(column(db, "SELECT id FROM merchant_attempt")).containsExactly("a1");
            assertThat(column(db, "SELECT id FROM approval")).containsExactly("p1");
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'index'"))
                    .contains("ux_intent_active_dedupe", "ix_intent_user", "ux_intent_order", "ix_intent_state_created",
                            "ix_intent_cancel_pending", "ix_approval_pending_expiry");
            // The children now point at the renamed parent: an orphan is still refused.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec(db,
                    "INSERT INTO intent_event (intent_id, seq, type, payload_json, at) VALUES ('ghost', 1, 't', '{}', 't')"))
                    .isInstanceOf(DatabaseException.class);
            // Dropping the old parent did not leave a dangling table behind.
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '%\\_new' ESCAPE '\\'"))
                    .isEmpty();
        }
    }

    // Covers: section 10, I1 (the schema refuses a COMPLETED intent with no order, and a second intent for one order)
    @Test
    void schema_refusesCompletedWithoutOrder_andOneOrderTwice() {
        Path file = dir.resolve("guards.db");
        Migrations.migrate(file);
        try (Database db = Database.open(file, 1)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec(db, intent("i1", "m", "k1", "COMPLETED", null)))
                    .isInstanceOf(DatabaseException.class);
            exec(db, intent("i2", "m", "k2", "COMPLETED", "o-1"), intent("i3", "other", "k3", "COMPLETED", "o-1"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec(db, intent("i4", "m", "k4", "COMPLETED", "o-1")))
                    .isInstanceOf(DatabaseException.class);
            assertThat(column(db, "SELECT id FROM purchase_intent ORDER BY id")).containsExactly("i2", "i3");
        }
    }

    // Covers: section 10 (the state, op and status lists in the CHECKs are exactly the Java enums' values)
    @Test
    void checks_listExactlyTheEnumValues() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V9__intent_handoff_url.sql"));

        // V9 is the newest definition of every list (it rebuilt the four tables, H5 C7a).
        assertThat(checkList(sql, "state")).isEqualTo(names(IntentState.values()));
        assertThat(checkList(sql, "op")).isEqualTo(names(dev.ucplab.core.intent.AttemptOp.values()));
        assertThat(checkList(sql, "status", "SENT")).isEqualTo(names(dev.ucplab.core.intent.AttemptStatus.values()));
        assertThat(checkList(sql, "status", "PENDING")).isEqualTo(names(dev.ucplab.core.approval.ApprovalStatus.values()));
    }

    private static TreeSet<String> names(Enum<?>[] values) {
        TreeSet<String> out = new TreeSet<>();
        for (Enum<?> e : values) {
            out.add(e.name());
        }
        return out;
    }

    /** The values of {@code <column> TEXT NOT NULL CHECK (<column> IN (...))} whose list contains {@code mustContain}. */
    private static TreeSet<String> checkList(String sql, String column, String... mustContain) {
        Matcher m = Pattern.compile(column + " TEXT NOT NULL CHECK \\(" + column + " IN \\(([^)]*)\\)\\)", Pattern.DOTALL)
                .matcher(sql);
        while (m.find()) {
            TreeSet<String> values = new TreeSet<>();
            for (String v : m.group(1).split(",")) {
                values.add(v.trim().replace("'", ""));
            }
            if (mustContain.length == 0 || values.contains(mustContain[0])) {
                return values;
            }
        }
        throw new AssertionError("no CHECK list for " + column);
    }

    // Covers: section 10 (V5 creates the run tables and their indexes on an empty database)
    @Test
    void v5_createsTheRunTables_fromEmpty() {
        Path file = dir.resolve("v5.db");
        Migrations.migrate(file);

        try (Database db = Database.open(file, 1)) {
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'table'"))
                    .contains("agent_run", "agent_run_intent", "agent_run_tool_event", "agent_run_turn");
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'index'"))
                    .contains("ix_run_user", "ix_run_intent", "ix_run_turn_user");
            assertThat(column(db, "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank"))
                    .contains("5");
        }
    }

    // Covers: section 10 (V5 on a database at V4 keeps its intents and adds the run tables)
    @Test
    void v5_onAV4Database_keepsItsRows() {
        Path file = dir.resolve("v4-to-v5.db");
        Flyway.configure().dataSource("jdbc:sqlite:" + file, null, null).locations("classpath:db/migration")
                .target("4").load().migrate();
        try (Database old = Database.open(file, 1)) {
            exec(old, intent("i1", "m", "k1", "AWAITING_APPROVAL", null));
            assertThat(column(old, "SELECT name FROM sqlite_master WHERE type = 'table'")).doesNotContain("agent_run");
        }

        Migrations.migrate(file);

        try (Database db = Database.open(file, 1)) {
            assertThat(column(db, "SELECT id FROM purchase_intent")).containsExactly("i1");
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'table'"))
                    .contains("agent_run", "agent_run_turn");
            exec(db, "INSERT INTO agent_run (id, user_id, model, started_at, updated_at) VALUES ('r', 'u', 'm', 't', 't')");
            assertThat(column(db, "SELECT id FROM agent_run")).containsExactly("r");
        }
    }

    // Covers: section 10, 23.1 (the outcome CHECK lists exactly the statuses a stored run can end with)
    @Test
    void v5_outcomeCheck_listsTheEndedStatuses() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V5__agent_run.sql"));
        Matcher m = Pattern.compile("outcome IN \\(([^)]*)\\)").matcher(sql);

        assertThat(m.find()).isTrue();
        assertThat(new TreeSet<>(List.of(m.group(1).replace("'", "").split(","))))
                .containsExactlyInAnyOrder("done", "waiting_for_approval", "budget_exceeded", "user_timeout", "failed");
    }

    // Covers: section 10, 18.5 (V6 creates app_user and user_session, from empty and on a database at V5)
    @Test
    void v6_createsTheUserAndSessionTables_fromEmptyAndFromV5() {
        Path empty = dir.resolve("v6.db");
        Migrations.migrate(empty);
        Path upgraded = dir.resolve("v5-to-v6.db");
        Flyway.configure().dataSource("jdbc:sqlite:" + upgraded, null, null).locations("classpath:db/migration")
                .target("5").load().migrate();
        try (Database old = Database.open(upgraded, 1)) {
            exec(old, "INSERT INTO agent_run (id, user_id, model, started_at, updated_at) VALUES ('r', 'u', 'm', 't', 't')");
        }
        Migrations.migrate(upgraded);

        for (Path file : List.of(empty, upgraded)) {
            try (Database db = Database.open(file, 1)) {
                assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'table'"))
                        .contains("app_user", "user_session").doesNotContain("login_state");
                assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'index'")).contains("ix_session_user");
                assertThat(column(db, "SELECT version FROM flyway_schema_history WHERE success = 1"
                        + " ORDER BY installed_rank")).contains("6");
            }
        }
        try (Database db = Database.open(upgraded, 1)) {
            assertThat(column(db, "SELECT id FROM agent_run")).containsExactly("r");
        }
    }

    // Covers: I11, 4.3, 10 (V7's trigger refuses a raw UPDATE of purchase_intent.merchant_id, by any writer, and lets an
    // update that leaves it as it is, and every other column, through)
    @Test
    void v7_aRawUpdateOfMerchantId_aborts_otherUpdatesDoNot() {
        Path file = dir.resolve("v7.db");
        Migrations.migrate(file);
        try (Database db = Database.open(file, 1)) {
            exec(db, intent("i1", "m", "k1", "PROPOSED", null));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec(db, "UPDATE purchase_intent SET merchant_id = 'other' WHERE id = 'i1'"))
                    .hasRootCauseMessage("[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the"
                            + " SQL statement to abort (I11: merchant_id never changes)");
            exec(db, "UPDATE purchase_intent SET merchant_id = merchant_id, state = 'QUOTING' WHERE id = 'i1'");

            assertThat(column(db, "SELECT merchant_id || ':' || state FROM purchase_intent")).containsExactly("m:QUOTING");
            assertThat(column(db, "SELECT version FROM flyway_schema_history WHERE success = 1"
                    + " ORDER BY installed_rank")).contains("7");
        }
    }

    // Covers: I11, 10 (a database migrated to V6 and holding rows upgrades to V7 with every row exactly as it was: V7
    // adds a trigger and rewrites nothing)
    @Test
    void v7_upgradesAV6DatabaseWithRows_leavingEveryRowUnchanged() {
        Path file = dir.resolve("v6-to-v7.db");
        Flyway.configure().dataSource("jdbc:sqlite:" + file, null, null).locations("classpath:db/migration")
                .target("6").load().migrate();
        String dump = "SELECT group_concat(id || '|' || user_id || '|' || merchant_id || '|' || state || '|' || version"
                + " || '|' || created_at, ';') FROM (SELECT * FROM purchase_intent ORDER BY id)";
        List<String> before;
        try (Database old = Database.open(file, 1)) {
            exec(old, intent("i1", "merchant-a", "k1", "PROPOSED", null));
            exec(old, intent("i2", "merchant-b", "k2", "QUOTING", null));
            before = column(old, dump);
            assertThat(column(old, "SELECT name FROM sqlite_master WHERE type = 'trigger'")).isEmpty();
        }

        Migrations.migrate(file);

        try (Database db = Database.open(file, 1)) {
            assertThat(column(db, dump)).isEqualTo(before);
            assertThat(column(db, "SELECT count(*) FROM purchase_intent")).containsExactly("2");
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'trigger'"))
                    .containsExactly("purchase_intent_merchant_immutable");
            assertThat(column(db, "SELECT version FROM flyway_schema_history WHERE success = 1"
                    + " ORDER BY installed_rank")).containsSubsequence("6", "7");
        }
    }

    // Covers: 10, 19, I12 (a database at V7 holding rows upgrades to V8: every row keeps every value it had and gets the
    // lab entry's fingerprint, the only entry an intent from before H5 can run under)
    @Test
    void v8_upgradesAV7DatabaseWithRows_givingEachTheLabFingerprint() {
        Path file = dir.resolve("v7-to-v8.db");
        Flyway.configure().dataSource("jdbc:sqlite:" + file, null, null).locations("classpath:db/migration")
                .target("7").load().migrate();
        String dump = "SELECT group_concat(id || '|' || user_id || '|' || merchant_id || '|' || state || '|' || version"
                + " || '|' || created_at, ';') FROM (SELECT * FROM purchase_intent ORDER BY id)";
        List<String> before;
        try (Database old = Database.open(file, 1)) {
            exec(old, intent("i1", "merchant-a", "k1", "PROPOSED", null));
            exec(old, intent("i2", "merchant-b", "k2", "QUOTING", null));
            before = column(old, dump);
        }

        Migrations.migrate(file);

        try (Database db = Database.open(file, 1)) {
            assertThat(column(db, dump)).isEqualTo(before);
            assertThat(column(db, "SELECT merchant_fingerprint FROM purchase_intent ORDER BY id")).containsExactly(
                    "{\"completion\":\"agent\",\"owned\":true,\"transport\":\"rest\"}",
                    "{\"completion\":\"agent\",\"owned\":true,\"transport\":\"rest\"}");
            assertThat(column(db, "SELECT version FROM flyway_schema_history WHERE success = 1"
                    + " ORDER BY installed_rank")).containsSubsequence("7", "8");
        }
    }

    // Covers: 10, 7.2, H5 C7a (a database at V8 holding rows, events, attempts and approvals upgrades to V9: every row
    // keeps every value it had, handoff_url starts empty, HANDED_OFF is accepted and terminal for the dedupe index, the
    // children point at the renamed parent, and the indexes and the I11 trigger are back; V9 follows V8)
    @Test
    void v9_upgradesAV8DatabaseWithRows_andAcceptsHandedOff() {
        Path file = dir.resolve("v8-to-v9.db");
        Flyway.configure().dataSource("jdbc:sqlite:" + file, null, null).locations("classpath:db/migration")
                .target("8").load().migrate();
        String dump = "SELECT group_concat(id || '|' || user_id || '|' || merchant_id || '|' || merchant_fingerprint"
                + " || '|' || state || '|' || version || '|' || created_at, ';') FROM (SELECT * FROM purchase_intent"
                + " ORDER BY id)";
        List<String> before;
        try (Database old = Database.open(file, 1)) {
            exec(old, intent("i1", "merchant-a", "k1", "APPROVED", null),
                    intent("i2", "merchant-b", "k2", "COMPLETED", "o2"),
                    "INSERT INTO intent_event (intent_id, seq, type, payload_json, at) VALUES ('i1', 1, 't', '{}', 't')",
                    "INSERT INTO merchant_attempt (id, intent_id, seq, op, revision, status, sent_at)"
                            + " VALUES ('a1', 'i1', 1, 'CREATE', 0, 'SENT', 't')",
                    "INSERT INTO approval (id, intent_id, seq, user_id, snapshot_json, snapshot_hash, token_hash,"
                            + " expires_at, status) VALUES ('p1', 'i1', 1, 'u', '{}', 'h', 'th', 't', 'APPROVED')");
            before = column(old, dump);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> exec(old, "UPDATE purchase_intent SET state = 'HANDED_OFF' WHERE id = 'i1'"))
                    .as("V8 does not know HANDED_OFF").isInstanceOf(DatabaseException.class);
        }

        Migrations.migrate(file);

        try (Database db = Database.open(file, 1)) {
            assertThat(column(db, dump)).isEqualTo(before);
            assertThat(column(db, "SELECT count(*) FROM purchase_intent WHERE handoff_url IS NULL")).containsExactly("2");
            assertThat(column(db, "SELECT type FROM intent_event")).containsExactly("t");
            assertThat(column(db, "SELECT id FROM merchant_attempt")).containsExactly("a1");
            assertThat(column(db, "SELECT status FROM approval")).containsExactly("APPROVED");
            exec(db, "UPDATE purchase_intent SET state = 'HANDED_OFF', handoff_url = 'https://shop.example/c'"
                    + " WHERE id = 'i1'");
            // HANDED_OFF is terminal: a new active intent may take its dedupe key.
            exec(db, intent("i3", "merchant-a", "k1", "PROPOSED", null));
            // The children point at the renamed parent: a row for an existing intent is taken, an orphan refused.
            exec(db, "INSERT INTO intent_event (intent_id, seq, type, payload_json, at) VALUES ('i3', 1, 't', '{}', 't')");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec(db,
                    "INSERT INTO intent_event (intent_id, seq, type, payload_json, at) VALUES ('ghost', 1, 't', '{}', 't')"))
                    .isInstanceOf(DatabaseException.class);
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '%\\_new' ESCAPE '\\'"))
                    .isEmpty();
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'index'"))
                    .contains("ux_intent_active_dedupe", "ix_intent_user", "ux_intent_order", "ix_intent_state_created",
                            "ix_intent_cancel_pending", "ix_approval_pending_expiry");
            assertThat(column(db, "SELECT name FROM sqlite_master WHERE type = 'trigger'"))
                    .containsExactly("purchase_intent_merchant_immutable");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> exec(db, "UPDATE purchase_intent SET merchant_id = 'other' WHERE id = 'i1'"))
                    .hasStackTraceContaining("I11");
            assertThat(column(db, "SELECT version FROM flyway_schema_history WHERE success = 1"
                    + " ORDER BY installed_rank")).containsSubsequence("8", "9");
        }
    }

    // Covers: 10, 7.2 hand_off, H5 C7b (the schema keeps a handoff_url only on a HANDED_OFF intent: a raw insert or
    // update that writes one in any other state is refused; HANDED_OFF takes it, and NULL is taken in every state)
    @Test
    void v9_handoffUrl_isRefusedInEveryStateButHandedOff() {
        Path file = dir.resolve("v9-handoff.db");
        Migrations.migrate(file);
        try (Database db = Database.open(file, 1)) {
            int n = 0;
            for (IntentState state : IntentState.values()) {
                String id = "i" + n++;
                String order = state == IntentState.COMPLETED ? "'o-" + id + "'" : "NULL";
                String insert = "INSERT INTO purchase_intent (id, user_id, merchant_id, dedupe_key, lines_json,"
                        + " buyer_email, state, version, order_id, created_at, updated_at, handoff_url) VALUES ('" + id
                        + "', 'u', 'm', 'k-" + id + "', '[]', 'e', '" + state.name() + "', 0, " + order
                        + ", 't', 't', %s)";
                if (state == IntentState.HANDED_OFF) {
                    exec(db, String.format(insert, "'https://shop.example/c'"));
                    continue;
                }
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> exec(db, String.format(insert, "'https://shop.example/c'")))
                        .as(state.name()).isInstanceOf(DatabaseException.class)
                        .hasStackTraceContaining("CHECK");
                exec(db, String.format(insert, "NULL"));
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec(db,
                        "UPDATE purchase_intent SET handoff_url = 'https://shop.example/c' WHERE id = '" + id + "'"))
                        .as(state.name() + " update").isInstanceOf(DatabaseException.class);
            }
            assertThat(column(db, "SELECT count(*) FROM purchase_intent WHERE handoff_url IS NOT NULL"))
                    .containsExactly("1");
        }
    }

    // Covers: 10, I12 (merchant_fingerprint is NOT NULL: a raw insert that sets it to NULL is refused)
    @Test
    void v8_merchantFingerprint_isNotNull() {
        Path file = dir.resolve("v8.db");
        Migrations.migrate(file);
        try (Database db = Database.open(file, 1)) {
            exec(db, intent("i1", "m", "k1", "PROPOSED", null));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> exec(db, "UPDATE purchase_intent SET merchant_fingerprint = NULL WHERE id = 'i1'"))
                    .hasStackTraceContaining("NOT NULL");
        }
    }

    // Covers: 23.4 /readyz (a database file is current only once every migration is applied: an empty file and a file
    // one migration behind are not; isCurrent never migrates)
    @Test
    void isCurrent_onlyAfterEveryMigration_andNeverMigrates() {
        Path empty = dir.resolve("empty.db");
        Path behind = dir.resolve("behind.db");
        Flyway.configure().dataSource("jdbc:sqlite:" + behind, null, null).locations("classpath:db/migration")
                .target("5").load().migrate();
        Path current = dir.resolve("current.db");
        Migrations.migrate(current);

        assertThat(Migrations.isCurrent(empty)).isFalse();
        assertThat(Migrations.isCurrent(behind)).isFalse();
        assertThat(Migrations.isCurrent(behind)).as("still behind: isCurrent did not migrate").isFalse();
        assertThat(Migrations.isCurrent(current)).isTrue();
    }

    // Covers: 10 V10, 7.6 (a database at V9 holding rows upgrades to V10: every row keeps every value it had and the four
    // follow-up columns start empty, so an intent handed off before V10 is not followed; a store order is refused in any
    // state but HANDED_OFF; V10 is the last migration)
    @Test
    void v10_upgradesAV9DatabaseWithRows_addingTheFollowUpColumnsEmpty() {
        Path file = dir.resolve("v9-to-v10.db");
        Flyway.configure().dataSource("jdbc:sqlite:" + file, null, null).locations("classpath:db/migration")
                .target("9").load().migrate();
        String dump = "SELECT group_concat(id || '|' || user_id || '|' || merchant_id || '|' || merchant_fingerprint"
                + " || '|' || state || '|' || version || '|' || coalesce(handoff_url, '-') || '|' || created_at, ';')"
                + " FROM (SELECT * FROM purchase_intent ORDER BY id)";
        List<String> before;
        try (Database old = Database.open(file, 1)) {
            exec(old, intent("i1", "merchant-a", "k1", "APPROVED", null),
                    intent("i2", "merchant-b", "k2", "COMPLETED", "o2"),
                    "UPDATE purchase_intent SET state = 'HANDED_OFF', handoff_url = 'https://shop.example/c' WHERE id = 'i1'");
            before = column(old, dump);
        }

        Migrations.migrate(file);

        try (Database db = Database.open(file, 1)) {
            assertThat(column(db, dump)).isEqualTo(before);
            assertThat(column(db, "SELECT count(*) FROM purchase_intent WHERE handoff_watch_until IS NULL"
                    + " AND handoff_next_read_at IS NULL AND store_order_id IS NULL AND store_order_label IS NULL"))
                    .containsExactly("2");
            exec(db, "UPDATE purchase_intent SET store_order_id = 'gid://shopify/Order/1', store_order_label = 'L1',"
                    + " handoff_watch_until = NULL WHERE id = 'i1'");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> exec(db, "UPDATE purchase_intent SET store_order_id = 'gid://shopify/Order/2' WHERE id = 'i2'"))
                    .as("a store order belongs to HANDED_OFF").isInstanceOf(DatabaseException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> exec(db, "UPDATE purchase_intent SET handoff_watch_until = 't' WHERE id = 'i2'"))
                    .as("a follow-up window belongs to HANDED_OFF").isInstanceOf(DatabaseException.class);
            assertThat(column(db, "SELECT version FROM flyway_schema_history WHERE success = 1"
                    + " ORDER BY installed_rank")).endsWith("9", "10");
        }
    }
}
