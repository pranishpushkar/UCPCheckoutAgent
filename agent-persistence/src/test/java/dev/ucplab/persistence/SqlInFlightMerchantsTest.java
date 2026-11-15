package dev.ucplab.persistence;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.intent.InFlightMerchantsContract;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The in-flight merchants contract (DESIGN 19, 18.1) against SQLite in a temp file. */
class SqlInFlightMerchantsTest extends InFlightMerchantsContract {

    @TempDir
    Path dir;

    private Database db;

    @Override
    protected void create(MutableClock clock) {
        Path file = dir.resolve("inflight.db");
        Migrations.migrate(file);
        db = Database.open(file, 4);
        intents = new SqlIntentRepository(db, clock);
        inFlight = new SqlRecoveryScan(db);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
