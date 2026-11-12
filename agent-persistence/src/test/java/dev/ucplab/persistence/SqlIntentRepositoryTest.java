package dev.ucplab.persistence;

import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentRepositoryContract;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The whole shared intent contract (7.3, 7.6, 7.7, 7.8, T2, T6, I4) against SQLite in a temp file. */
class SqlIntentRepositoryTest extends IntentRepositoryContract {

    @TempDir
    Path dir;

    private Database db;
    private SqlIntentRepository sql;

    @Override
    protected IntentRepository create(Clock clock) {
        Path file = dir.resolve("intents.db");
        Migrations.migrate(file);
        // The contract races 8 writers; a pool of 8 lets them all reach BEGIN IMMEDIATE.
        db = Database.open(file, 8);
        sql = new SqlIntentRepository(db, clock);
        return sql;
    }

    @Override
    protected List<IntentEvent> events(IntentId id) {
        return sql.events(id);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
