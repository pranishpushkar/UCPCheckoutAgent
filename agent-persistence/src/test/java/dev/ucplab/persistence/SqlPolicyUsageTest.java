package dev.ucplab.persistence;

import dev.ucplab.core.policy.PolicyUsageContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/** The SQL {@link dev.ucplab.core.policy.PolicyUsage} runs the usage contract (H3 P3; 9.1 P3 and P5, T5, section 20). */
class SqlPolicyUsageTest extends PolicyUsageContract {

    private Path dir;
    private Database db;

    @Override
    protected Stores create(Clock clock) {
        try {
            dir = Files.createTempDirectory("usage");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Path file = dir.resolve("usage.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        return new Stores(new SqlIntentRepository(db, clock), new SqlApprovalRepository(db), new SqlPolicyUsage(db));
    }

    @Override
    protected void close() {
        db.close();
    }
}
