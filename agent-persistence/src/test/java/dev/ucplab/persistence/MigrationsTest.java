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

    // Covers: 7.7 (the terminal states named in the partial unique index are exactly IntentState's terminal states, so
    // the SQL and the in-memory repository agree on what is active)
    @Test
    void dedupeIndex_namesExactlyTheTerminalStates() throws IOException {
        for (String file : List.of("V1__purchase_intent_and_events.sql", "V4__recovery_indexes.sql")) {
            assertDedupeListMatchesEnum(Files.readString(Path.of("src/main/resources/db/migration/" + file)));
        }
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
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V4__recovery_indexes.sql"));

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
}
