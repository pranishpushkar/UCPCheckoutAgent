package dev.ucplab.persistence;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.intent.RecoveryScanContract;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The shared recovery-scan contract (7.6) against SQLite in a temp file. */
class SqlRecoveryScanTest extends RecoveryScanContract {

    @TempDir
    Path dir;

    private Database db;

    @Override
    protected void create(MutableClock clock) {
        Path file = dir.resolve("scan.db");
        Migrations.migrate(file);
        db = Database.open(file, 4);
        intents = new SqlIntentRepository(db, clock);
        approvals = new SqlApprovalRepository(db);
        scan = new SqlRecoveryScan(db);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
