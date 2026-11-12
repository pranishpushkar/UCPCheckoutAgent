package dev.ucplab.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.sqlite.SQLiteConfig;

/**
 * A small connection pool over one SQLite file (ADR 0002, 7.8). Connections wait in an {@link ArrayBlockingQueue}; there
 * is no {@code synchronized} and no lock around JDBC. A connection is borrowed for one {@link #read} or {@link #write}
 * and is back in the pool when that call returns, so no connection is held across a merchant call.
 *
 * <p>Connections rest in auto-commit mode. {@link #write} runs its work in {@code BEGIN IMMEDIATE}: the driver issues
 * that statement when auto-commit is switched off (sqlite-jdbc {@code SQLiteConnection.setAutoCommit}, with
 * {@code transaction_mode=IMMEDIATE}). The transaction is ended by switching auto-commit back on, which issues
 * {@code commit;} and nothing more. {@code Connection.commit()} is not used: the driver follows it with a new
 * {@code begin immediate;}, which would hold the write lock on an idle pooled connection and could fail with
 * {@code SQLITE_BUSY} after the commit had already succeeded.
 */
public final class Database implements AutoCloseable {

    private static final Duration BORROW_TIMEOUT = Duration.ofSeconds(30);

    private final String url;
    private final SQLiteConfig config;
    private final BlockingQueue<Connection> idle;
    private final int poolSize;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Database(String url, SQLiteConfig config, int poolSize) {
        this.url = url;
        this.config = config;
        this.idle = new ArrayBlockingQueue<>(poolSize);
        this.poolSize = poolSize;
    }

    /**
     * Connections borrowed right now. Zero while the orchestrator calls the merchant: no connection is held across
     * network I/O (7.8).
     */
    public int inUse() {
        return poolSize - idle.size();
    }

    /** Opens the file (creating it when absent) with {@code poolSize} connections. Run {@link Migrations} before use. */
    public static Database open(Path file, int poolSize) {
        Objects.requireNonNull(file, "file");
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be at least 1: " + poolSize);
        }
        Database db = new Database(urlOf(file), sqliteConfig(), poolSize);
        try {
            for (int i = 0; i < poolSize; i++) {
                db.idle.add(db.connect());
            }
        } catch (SQLException e) {
            db.close();
            throw new DatabaseException("cannot open " + file, e);
        }
        return db;
    }

    static String urlOf(Path file) {
        return "jdbc:sqlite:" + file;
    }

    /** WAL, busy_timeout 5000, foreign keys on, and every transaction started with BEGIN IMMEDIATE (ADR 0002). */
    static SQLiteConfig sqliteConfig() {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return config;
    }

    private Connection connect() throws SQLException {
        return config.createConnection(url);
    }

    /** Runs read-only work in auto-commit mode: each statement sees a consistent snapshot, and no write lock is taken. */
    public <T> T read(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        Connection c = borrow();
        boolean healthy = false;
        try {
            T result = work.run(c);
            healthy = true;
            return result;
        } catch (SQLException e) {
            healthy = isUsable(c);
            throw new DatabaseException("read failed", e);
        } catch (RuntimeException e) {
            healthy = isUsable(c);
            throw e;
        } finally {
            giveBack(c, healthy);
        }
    }

    /**
     * Runs {@code work} in one {@code BEGIN IMMEDIATE} transaction. It commits when the work returns and rolls back when
     * it throws; a {@link RuntimeException} from the work is rethrown as it is, so domain exceptions pass through.
     */
    public <T> T write(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        Connection c = borrow();
        boolean healthy = false;
        boolean begun = false;
        try {
            c.setAutoCommit(false);
            begun = true;
            T result = work.run(c);
            c.setAutoCommit(true);
            healthy = true;
            return result;
        } catch (SQLException e) {
            healthy = begun && rollBack(c);
            throw new DatabaseException("write failed", e);
        } catch (RuntimeException e) {
            healthy = begun && rollBack(c);
            throw e;
        } finally {
            giveBack(c, healthy);
        }
    }

    /** Rolls back and returns the connection to auto-commit mode. False when it could not, so the caller discards it. */
    private static boolean rollBack(Connection c) {
        try {
            c.rollback();
            c.setAutoCommit(true);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean isUsable(Connection c) {
        try {
            return !c.isClosed() && c.getAutoCommit();
        } catch (SQLException e) {
            return false;
        }
    }

    private Connection borrow() {
        if (closed.get()) {
            throw new DatabaseException("database is closed");
        }
        try {
            Connection c = idle.poll(BORROW_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (c == null) {
                throw new DatabaseException("no connection free after " + BORROW_TIMEOUT);
            }
            return c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("interrupted while waiting for a connection", e);
        }
    }

    /** A connection in an unknown state is closed (which rolls back) and replaced, so the pool keeps its size. */
    private void giveBack(Connection c, boolean healthy) {
        Connection toReturn = c;
        if (!healthy) {
            closeQuietly(c);
            try {
                toReturn = connect();
            } catch (SQLException e) {
                throw new DatabaseException("cannot replace a discarded connection", e);
            }
        }
        if (closed.get() || !idle.offer(toReturn)) {
            closeQuietly(toReturn);
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            // Nothing to do: the connection is being thrown away.
        }
    }

    /** Closes the idle connections. A connection that is borrowed at the time is closed when it comes back. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Connection c;
            while ((c = idle.poll()) != null) {
                closeQuietly(c);
            }
        }
    }
}
