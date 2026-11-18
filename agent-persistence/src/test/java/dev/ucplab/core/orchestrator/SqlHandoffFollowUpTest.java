package dev.ucplab.core.orchestrator;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Migrations;
import dev.ucplab.persistence.SqlApprovalRepository;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.persistence.SqlRecoveryScan;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port parity for the handoff follow-up (7.6, 10 V10): every case of {@link HandoffFollowUpTest} over the SQL adapters,
 * each lab on its own migrated SQLite file, so the window, the next read, the store's order and the claim of a due
 * follow-up go through V10's columns. The crash case keeps its own in-memory lab, whose crash plan wraps the port.
 */
class SqlHandoffFollowUpTest extends HandoffFollowUpTest {

    @TempDir
    static Path dir;

    private static final AtomicInteger FILES = new AtomicInteger();
    private static final List<Database> OPEN = new ArrayList<>();

    @AfterAll
    static void closeDatabases() {
        OPEN.forEach(Database::close);
    }

    static CrashHarness.Stores sqlStores(MutableClock clock) {
        Path file = dir.resolve("follow-up-" + FILES.incrementAndGet() + ".db");
        Migrations.migrate(file);
        Database db = Database.open(file, 2);
        synchronized (OPEN) {
            OPEN.add(db);
        }
        return new CrashHarness.Stores(new SqlIntentRepository(db, clock), new SqlApprovalRepository(db),
                new SqlRecoveryScan(db));
    }

    @Override
    protected Lab newLab() {
        Lab lab = new Lab(OrchestratorConfig.defaults(), FaultyMerchantModel.Mode.LAB, SqlHandoffFollowUpTest::sqlStores);
        lab.eventLog = ((SqlIntentRepository) lab.intents)::events;
        return lab;
    }
}
