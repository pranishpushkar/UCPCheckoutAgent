package dev.ucplab.persistence;

import dev.ucplab.core.intent.IntentQueriesContract;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The shared owner-scoped read contract (23.2, T2, T6, I9) against SQLite in a temp file. */
class SqlIntentQueriesTest extends IntentQueriesContract {

    @TempDir
    Path dir;

    private Database db;

    @Override
    protected Stores create(Clock clock) {
        Path file = dir.resolve("queries.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        return new Stores(new SqlIntentRepository(db, clock), new SqlIntentQueries(db));
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
