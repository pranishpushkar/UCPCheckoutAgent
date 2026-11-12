package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

/** The build setup of ADR 0002: the driver, its settings, Flyway on SQLite, and the agent-core test-jar. */
class SqliteSetupTest {

    @TempDir
    Path dir;

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("setup.db");
    }

    private Connection open(int busyMillis) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(busyMillis);
        config.enforceForeignKeys(true);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return config.createConnection(url());
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getString(1) : "";
        }
    }

    // Covers: ADR 0002 (WAL, busy_timeout and foreign keys are applied to a connection)
    @Test
    void sqlite_connectionSettings_areApplied() throws SQLException {
        try (Connection c = open(5000)) {
            assertThat(scalar(c, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(scalar(c, "PRAGMA foreign_keys")).isEqualTo("1");
            assertThat(scalar(c, "PRAGMA busy_timeout")).isEqualTo("5000");
        }
    }

    // Covers: ADR 0002, 7.8 (a write transaction takes the write lock at BEGIN, so a second writer waits, then fails
    // with SQLITE_BUSY, instead of deadlocking on a read-to-write upgrade)
    @Test
    void sqlite_immediateTransaction_blocksSecondWriterAtBegin() throws SQLException {
        try (Connection first = open(50); Connection second = open(50)) {
            first.setAutoCommit(false);
            assertThatThrownBy(() -> second.setAutoCommit(false))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("BUSY");
            first.rollback();
        }
    }

    // Covers: ADR 0002 (Flyway 13 migrates a SQLite file with no extra database module)
    @Test
    void flyway_migratesSqliteFile_withoutExtraModules() throws SQLException {
        Flyway.configure().dataSource(url(), null, null).locations("classpath:db/smoke").load().migrate();
        try (Connection c = open(5000)) {
            assertThat(scalar(c, "SELECT count(*) FROM smoke")).isEqualTo("0");
            assertThat(scalar(c, "SELECT version FROM flyway_schema_history WHERE success = 1")).isEqualTo("1");
        }
    }

    // Covers: ADR 0002 (the agent-core test-jar is on the test classpath)
    @Test
    void coreTestJar_isOnTestClasspath_always() {
        assertThat(new MutableClock(Instant.parse("2026-01-01T00:00:00Z")).instant())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
