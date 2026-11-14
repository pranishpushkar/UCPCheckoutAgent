package dev.ucplab.persistence;

import dev.ucplab.core.approval.ApprovalMerchantContract;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The approval-merchant contract (I11, 9.2) against SQLite. */
class SqlApprovalMerchantTest extends ApprovalMerchantContract {

    @TempDir
    Path dir;

    private Database db;

    @Override
    protected void create(Clock clock) {
        Path file = dir.resolve("approvals.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        intents = new SqlIntentRepository(db, clock);
        approvals = new SqlApprovalRepository(db);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
