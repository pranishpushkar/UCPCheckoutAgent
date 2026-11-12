package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path dir;

    private Path file;
    private Database db;

    @BeforeEach
    void open() {
        file = dir.resolve("test.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getString(1) : "";
        }
    }

    private static String value(Database database, String sql) {
        return database.read(c -> scalar(c, sql));
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private void insertEvent(String intent, int seq) {
        db.write(c -> {
            exec(c, "INSERT INTO purchase_intent (id, user_id, merchant_id, dedupe_key, lines_json, buyer_email, state,"
                    + " version, created_at, updated_at) VALUES ('" + intent + "', 'u', 'm', 'd" + intent
                    + "', '[]', 'e', 'PROPOSED', 0, 't', 't')");
            return null;
        });
    }

    // Covers: ADR 0002 (WAL, busy_timeout and foreign keys are set on every pooled connection)
    @Test
    void connections_haveTheAdrSettings() {
        for (int i = 0; i < 2; i++) {
            List<String> settings = db.read(c -> List.of(
                    scalar(c, "PRAGMA journal_mode"), scalar(c, "PRAGMA busy_timeout"), scalar(c, "PRAGMA foreign_keys")));
            assertThat(settings).containsExactly("wal", "5000", "1");
        }
    }

    // Covers: 7.8, ADR 0002 (a write transaction commits when the work returns)
    @Test
    void write_commitsWhenWorkReturns() {
        insertEvent("i1", 1);

        assertThat(value(db, "SELECT count(*) FROM purchase_intent")).isEqualTo("1");
    }

    // Covers: 7.8 (a write that throws rolls back every statement, and a domain exception passes through unchanged)
    @Test
    void write_rollsBackAndRethrows_whenWorkThrows() {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> db.write(c -> {
            exec(c, "INSERT INTO purchase_intent (id, user_id, merchant_id, dedupe_key, lines_json, buyer_email, state,"
                    + " version, created_at, updated_at) VALUES ('i1', 'u', 'm', 'd', '[]', 'e', 'PROPOSED', 0, 't', 't')");
            throw boom;
        })).isSameAs(boom);

        assertThat(value(db, "SELECT count(*) FROM purchase_intent")).isEqualTo("0");
    }

    // Covers: 7.8 (an SQL error inside a write rolls back, and the pool still works afterwards)
    @Test
    void write_rollsBackAndStaysUsable_whenStatementFails() {
        assertThatThrownBy(() -> db.write(c -> {
            exec(c, "INSERT INTO purchase_intent (id, user_id, merchant_id, dedupe_key, lines_json, buyer_email, state,"
                    + " version, created_at, updated_at) VALUES ('i1', 'u', 'm', 'd', '[]', 'e', 'PROPOSED', 0, 't', 't')");
            exec(c, "INSERT INTO no_such_table VALUES (1)");
            return null;
        })).isInstanceOf(DatabaseException.class);

        assertThat(value(db, "SELECT count(*) FROM purchase_intent")).isEqualTo("0");
        insertEvent("i2", 1);
        assertThat(value(db, "SELECT count(*) FROM purchase_intent")).isEqualTo("1");
    }

    // Covers: ADR 0002, 7.8 (a connection that has finished a write is back in auto-commit mode and holds no write
    // lock: another connection of the pool can write straight away, which Connection.commit() would not allow)
    @Test
    void write_leavesNoWriteLockBehindOnTheConnection() {
        insertEvent("i1", 1);
        insertEvent("i2", 1);
        try (Database other = Database.open(file, 1)) {
            other.write(c -> {
                exec(c, "INSERT INTO purchase_intent (id, user_id, merchant_id, dedupe_key, lines_json, buyer_email,"
                        + " state, version, created_at, updated_at) VALUES ('i3', 'u', 'm', 'd3', '[]', 'e', 'PROPOSED',"
                        + " 0, 't', 't')");
                return null;
            });
        }

        assertThat(value(db, "SELECT count(*) FROM purchase_intent")).isEqualTo("3");
    }

    // Covers: 7.8 (concurrent writers queue behind BEGIN IMMEDIATE: none fails, none is lost)
    @Test
    void write_serialisesConcurrentWriters() throws Exception {
        int writers = 8;
        try (Database wide = Database.open(file, writers)) {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            try {
                List<Future<?>> done = new ArrayList<>();
                for (int i = 0; i < writers; i++) {
                    String id = "i" + i;
                    done.add(pool.submit(() -> {
                        start.await();
                        wide.write(c -> {
                            exec(c, "INSERT INTO purchase_intent (id, user_id, merchant_id, dedupe_key, lines_json,"
                                    + " buyer_email, state, version, created_at, updated_at) VALUES ('" + id
                                    + "', 'u', 'm', 'd" + id + "', '[]', 'e', 'PROPOSED', 0, 't', 't')");
                            return null;
                        });
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : done) {
                    f.get();
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(value(wide, "SELECT count(*) FROM purchase_intent")).isEqualTo("8");
        }
    }

    // Covers: 7.8 (a connection is returned to the pool when the call ends, success or failure, so a pool of one
    // serves many calls)
    @Test
    void pool_returnsConnectionsAfterEveryCall() {
        try (Database one = Database.open(file, 1)) {
            for (int i = 0; i < 5; i++) {
                one.read(c -> scalar(c, "SELECT 1"));
                assertThatThrownBy(() -> one.write(c -> {
                    throw new IllegalStateException("x");
                })).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> one.read(c -> scalar(c, "SELECT * FROM nope")))
                        .isInstanceOf(DatabaseException.class);
            }
            assertThat(value(one, "SELECT 1")).isEqualTo("1");
        }
    }

    // Covers: ADR 0002 (foreign keys are enforced: an event for a missing intent is refused by the database)
    @Test
    void foreignKeys_areEnforced() {
        assertThatThrownBy(() -> db.write(c -> {
            exec(c, "INSERT INTO intent_event (intent_id, seq, type, payload_json, at) VALUES ('ghost', 1, 't', '{}', 't')");
            return null;
        })).isInstanceOf(DatabaseException.class);
    }

    // Covers: 7.8 (a closed database refuses work instead of hanging)
    @Test
    void closedDatabase_refusesWork() {
        Database closed = Database.open(file, 1);
        closed.close();

        assertThatThrownBy(() -> closed.read(c -> scalar(c, "SELECT 1"))).isInstanceOf(DatabaseException.class);
    }
}
