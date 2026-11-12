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
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V1__purchase_intent_and_events.sql"));
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
}
