package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.merchant.SessionId;
import java.util.Optional;
import dev.ucplab.core.approval.ApprovalRepositoryContract;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.MerchantId;
import java.time.Instant;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

    @Override
    protected void storeIntent(IntentId id, UserId owner) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        new SqlIntentRepository(db, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)).insert(
                PurchaseIntent.proposed(id, owner, new MerchantId("shop-1"), "dedupe-" + id.value(),
                        List.of(new ProposedLine("item_123", 1)), "a@example.com", now));
    }

    // Covers: section 10 (an approval needs its intent: the database refuses an orphan)
    @Test
    void insert_isRefusedByTheDatabase_whenTheIntentDoesNotExist() {
        IntentId ghost = new IntentId("ghost");
        ApprovalSnapshot snapshot = new ApprovalSnapshot(ghost, new MerchantId("shop-1"), new SessionId("s"),
                List.of(new SnapshotLine("item_123", 1, 100)), "USD", 100, 100, Instant.parse("2026-01-01T00:10:00Z"));
        ApprovalRecord orphan = new ApprovalRecord("a-orphan", ghost, new UserId("alice"), snapshot, snapshot.hash(),
                "th", snapshot.expiresAt(), ApprovalStatus.PENDING, Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> repo.insert(orphan)).isInstanceOf(DatabaseException.class);
    }

    // Covers: 9.2, 10 (a snapshot stored before titles, with no title key in its JSON, reads back with empty titles and
    // its stored hash; the hash of a title-less snapshot is unchanged)
    @Test
    void aSnapshotStoredBeforeTitles_readsBackWithEmptyTitles() {
        IntentId id = new IntentId("i-old");
        storeIntent(id, new UserId("alice"));
        ApprovalSnapshot snapshot = new ApprovalSnapshot(id, new MerchantId("shop-1"), new SessionId("s"),
                List.of(new SnapshotLine("item_123", 1, 100)), "USD", 100, 100, Instant.parse("2026-01-01T00:10:00Z"));
        repo.insert(new ApprovalRecord("a-old", id, new UserId("alice"), snapshot, snapshot.hash(), "th",
                snapshot.expiresAt(), ApprovalStatus.PENDING, Optional.empty(), Optional.empty()));
        String stored = db.write(c -> {
            try (var s = c.prepareStatement("UPDATE approval SET snapshot_json = replace(replace(snapshot_json,"
                    + " '\"title\":\"\",', ''), ',\"title\":\"\"', '') WHERE id = 'a-old'")) {
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("SELECT snapshot_json FROM approval WHERE id = 'a-old'");
                    var r = s.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        });
        assertThat(stored).as("as stored before titles").doesNotContain("title");

        ApprovalRecord read = repo.findLatest(new UserId("alice"), id).orElseThrow();

        assertThat(read.snapshot().lines()).containsExactly(new SnapshotLine("item_123", 1, 100, ""));
        assertThat(read.snapshot().hash()).isEqualTo(read.snapshotHash());
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
