package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * H3 P9: the connection pool of 7.8 and ADR 0002, one test per surviving mutant: what is borrowed, what is replaced, what is
 * closed. A pool of one makes the identity of the connection visible.
 */
class DatabaseMutationTest {

    @TempDir
    Path dir;

    private Database one;

    @BeforeEach
    void open() {
        Path file = dir.resolve("pool.db");
        Migrations.migrate(file);
        one = Database.open(file, 1);
    }

    @AfterEach
    void close() {
        one.close();
    }

    private static boolean isClosed(Connection c) {
        try {
            return c.isClosed();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Connection connection() {
        return one.read(c -> c);
    }

    // Covers: 7.8 (no connection is held across a merchant call, so "in use" is the number borrowed right now). Kills
    // Database.inUse:49 PrimitiveReturns
    @Test
    void inUse_countsTheConnectionsBorrowedRightNow() {
        Path file = dir.resolve("pool3.db");
        Migrations.migrate(file);
        try (Database three = Database.open(file, 3)) {
            assertThat(three.inUse()).isZero();
            Integer one = three.read(c -> three.inUse());
            Integer two = three.read(c -> three.read(d -> three.inUse()));
            assertThat(one).isEqualTo(1);
            assertThat(two).isEqualTo(2);
            assertThat(three.inUse()).isZero();
        }
    }

    // Covers: 7.8. Kills Database.rollBack:140 BooleanFalseReturnVals and write:125, :128 RemoveConditional (ELSE): a connection
    // that rolled back cleanly is reused, not thrown away
    @Test
    void aWriteThatFails_leavesTheSameConnectionInThePool() {
        Connection before = connection();

        assertThatThrownBy(() -> one.write(c -> {
            throw new IllegalStateException("domain failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> one.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SELECT * FROM no_such_table");
            }
            return null;
        })).isInstanceOf(DatabaseException.class);

        assertThat(connection()).isSameAs(before);
        assertThat(isClosed(before)).isFalse();
    }

    // Covers: 7.8. Kills Database.giveBack:174 (closeQuietly of a discarded connection), :181 and rollBack:142 (NO_COVERAGE): a
    // connection that cannot be rolled back is closed and replaced by a new one, and the pool keeps its size
    @Test
    void aConnectionThatCannotBeRolledBack_isClosedAndReplaced() throws SQLException {
        Connection before = connection();

        assertThatThrownBy(() -> one.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("ROLLBACK");
            }
            throw new IllegalStateException("the transaction is gone");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(before.isClosed()).as("the discarded connection is closed").isTrue();
        Connection after = connection();
        assertThat(after).isNotSameAs(before);
        assertThat(after.isClosed()).isFalse();
        assertThat(one.inUse()).isZero();
        Integer answer = one.read(c -> c.createStatement().executeQuery("SELECT 1").getInt(1));
        assertThat(answer).isEqualTo(1);
    }

    // Covers: 7.8, 7.3 (a write is committed, and its connection back in auto-commit mode, when the work returns: the next connection
    // sees the row at once, and a failed write leaves nothing behind). Kills Database.write:121 and rollBack:139 VoidMethodCall
    // (both timed out: a transaction left open holds the write lock, and the next writer waits out the 5 s busy timeout); this
    // test fails at once instead
    @Test
    void aCommittedWrite_isVisibleToTheNextConnection_withoutWaiting() throws SQLException {
        Path file = dir.resolve("two.db");
        Migrations.migrate(file);
        try (Database two = Database.open(file, 2)) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                two.write(c -> {
                    try (Statement s = c.createStatement()) {
                        s.execute("CREATE TABLE probe (n INTEGER)");
                        s.execute("INSERT INTO probe VALUES (1)");
                    }
                    return null;
                });
                Integer seen = two.read(c -> {
                    try (Statement s = c.createStatement(); java.sql.ResultSet r = s.executeQuery("SELECT COUNT(*) FROM probe")) {
                        return r.getInt(1);
                    }
                });
                assertThat(seen).isEqualTo(1);
                assertThatThrownBy(() -> two.write(c -> {
                    try (Statement s = c.createStatement()) {
                        s.execute("INSERT INTO probe VALUES (2)");
                    }
                    throw new IllegalStateException("undo");
                })).isInstanceOf(IllegalStateException.class);
                Integer after = two.write(c -> {
                    try (Statement s = c.createStatement(); java.sql.ResultSet r = s.executeQuery("SELECT COUNT(*) FROM probe")) {
                        return r.getInt(1);
                    }
                });
                assertThat(after).isEqualTo(1);
            });
        }
    }

    // Covers: 7.8. Kills Database.write:125 RemoveConditional_EQUAL_IF (the rollback of a failed statement): a statement failure
    // after which the transaction cannot be rolled back also costs the connection
    @Test
    void aStatementFailureThatCannotBeRolledBack_alsoReplacesTheConnection() throws SQLException {
        Connection before = connection();

        assertThatThrownBy(() -> one.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("ROLLBACK");
                s.execute("SELECT * FROM no_such_table");
            }
            return null;
        })).isInstanceOf(DatabaseException.class);

        assertThat(before.isClosed()).isTrue();
        assertThat(connection()).isNotSameAs(before);
    }

    // Covers: 7.8. Kills Database.isUsable:148, :150 (BooleanTrueReturnVals, RemoveConditional) and read:101: a read whose work fails
    // keeps a usable connection, and replaces one that is no longer usable
    @Test
    void aReadThatFails_keepsAUsableConnection_andReplacesABrokenOne() throws SQLException {
        Connection healthy = connection();
        assertThatThrownBy(() -> one.read(c -> {
            throw new SQLException("a failed statement");
        })).isInstanceOf(DatabaseException.class).hasMessage("read failed");
        assertThat(connection()).as("a failed statement leaves the connection usable").isSameAs(healthy);

        assertThatThrownBy(() -> one.read(c -> {
            c.close();
            throw new SQLException("the connection died");
        })).isInstanceOf(DatabaseException.class);
        Connection replaced = connection();
        assertThat(replaced).isNotSameAs(healthy);
        assertThat(replaced.isClosed()).isFalse();

        assertThatThrownBy(() -> one.read(c -> {
            c.setAutoCommit(false);
            throw new IllegalStateException("left in a transaction");
        })).isInstanceOf(IllegalStateException.class);
        Connection fresh = connection();
        assertThat(fresh).as("a connection left outside auto-commit is replaced").isNotSameAs(replaced);
        assertThat(fresh.getAutoCommit()).isTrue();
    }

    // Covers: 7.8. Kills Database.close:197, :199, :200 (RemoveConditional, closeQuietly) and giveBack:182 (NO_COVERAGE): close
    // closes the idle connections, a second close does nothing, a connection that is out at the time is closed when it comes back,
    // and no work is accepted afterwards
    @Test
    void close_closesIdleConnections_andThoseThatComeBackLater() throws SQLException {
        Connection idle = connection();
        List<Connection> borrowed = new ArrayList<>();
        one.read(c -> {
            borrowed.add(c);
            one.close();
            return null;
        });

        assertThat(borrowed.get(0).isClosed()).as("the one that was out when close was called").isTrue();
        assertThat(idle.isClosed()).isTrue();
        one.close();
        // Within two seconds: a pool that forgot it was closed would wait out the 30 s borrow timeout for a connection that is gone.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThatThrownBy(() -> one.read(c -> 1)).isInstanceOf(DatabaseException.class).hasMessage("database is closed");
            assertThatThrownBy(() -> one.write(c -> 1)).isInstanceOf(DatabaseException.class).hasMessage("database is closed");
        });
    }

    @Test
    void close_closesEveryIdleConnectionOfALargerPool() throws SQLException {
        Path file = dir.resolve("pool4.db");
        Migrations.migrate(file);
        Database four = Database.open(file, 4);
        List<Connection> all = new ArrayList<>();
        four.read(a -> four.read(b -> four.read(c -> four.read(d -> {
            all.addAll(List.of(a, b, c, d));
            return null;
        }))));

        four.close();

        for (Connection c : all) {
            assertThat(c.isClosed()).isTrue();
        }
    }

    // Covers: 7.8. Kills Database.borrow:165 VoidMethodCall (NO_COVERAGE: Thread.interrupt): a thread that is interrupted while it
    // waits for a connection gets a DatabaseException and keeps its interrupt flag
    @Test
    void anInterruptedThread_isRefusedAConnection_andKeepsItsFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> one.read(c -> 1)).isInstanceOf(DatabaseException.class)
                    .hasMessageContaining("interrupted while waiting for a connection");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // Covers: 7.8. Kills Database.open:64 VoidMethodCall (NO_COVERAGE) and open:60: a file that cannot be opened is refused with its
    // name, and a pool of fewer than one connection is refused
    @Test
    void aFileThatCannotBeOpened_isRefused() {
        Path missing = dir.resolve("no-such-dir").resolve("x.db");

        assertThatThrownBy(() -> Database.open(missing, 2)).isInstanceOf(DatabaseException.class)
                .hasMessageContaining("cannot open");
        assertThatThrownBy(() -> Database.open(dir.resolve("pool.db"), 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poolSize must be at least 1");
    }
}
