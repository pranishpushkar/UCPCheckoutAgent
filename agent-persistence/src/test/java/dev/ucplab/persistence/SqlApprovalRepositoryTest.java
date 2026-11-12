package dev.ucplab.persistence;

import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalRepositoryContract;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The shared approval contract (9.2, T2, T6, I10) against SQLite in a temp file. */
class SqlApprovalRepositoryTest extends ApprovalRepositoryContract {

    @TempDir
    Path dir;

    private Database db;

    @Override
    protected ApprovalRepository create() {
        Path file = dir.resolve("approvals.db");
        Migrations.migrate(file);
        // The contract races 8 callers; a pool of 8 lets them all reach BEGIN IMMEDIATE.
        db = Database.open(file, 8);
        return new SqlApprovalRepository(db);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
