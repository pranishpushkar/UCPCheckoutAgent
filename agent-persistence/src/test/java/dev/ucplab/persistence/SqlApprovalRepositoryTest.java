package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.LinkSnapshot;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.merchant.SessionId;
import java.util.Optional;
import dev.ucplab.core.approval.ApprovalRepositoryContract;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.ContinueUrl;
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

    // Covers: 9.2, 10, I2, I3, 26.7 (a link intent's snapshot keeps its link through the database: the seller's name,
    // domain and site, and the checkout_url exactly, so the read-back snapshot equals the stored one and its hash)
    @Test
    void aLinkSnapshot_readsBackWithItsLinkExactly() {
        IntentId id = new IntentId("i-link");
        storeIntent(id, new UserId("alice"));
        String url = "https://www.shop-a.example/buy/1000001:1?_gsid=FAKEGSID0001&attribution%2Fclick_id_tag=FAKETAG";
        ApprovalSnapshot snapshot = new ApprovalSnapshot(id, new MerchantId("shopify-catalog"),
                new SessionId("link:gid://shopify/ProductVariant/1000001"),
                List.of(new SnapshotLine("gid://shopify/ProductVariant/1000001", 1, 30495, "Example Snowboard 1")),
                "USD", 30495, 30495, Instant.parse("2026-01-01T00:10:00Z"), Optional.empty(),
                Optional.of(new LinkSnapshot("Example Shop A", "shop-a.myshopify.example",
                        Optional.of("www.shop-a.example"), ContinueUrl.parse(url).orElseThrow())));
        repo.insert(new ApprovalRecord("a-link", id, new UserId("alice"), snapshot, snapshot.hash(), "th",
                snapshot.expiresAt(), ApprovalStatus.PENDING, Optional.empty(), Optional.empty()));

        ApprovalRecord read = repo.findLatest(new UserId("alice"), id).orElseThrow();

        assertThat(read.snapshot()).isEqualTo(snapshot);
        assertThat(read.snapshot().link().orElseThrow().url().value().toString()).isEqualTo(url);
        assertThat(read.snapshot().hash()).isEqualTo(read.snapshotHash());
    }

    // Covers: 9.2, 10, I8, 26.7 (a stored link whose url no longer parses is refused on read, never read as no link,
    // and the refusal names no part of it)
    @Test
    void aStoredLinkThatDoesNotParse_isRefusedOnRead_withoutNamingIt() {
        IntentId id = new IntentId("i-bad-link");
        storeIntent(id, new UserId("alice"));
        ApprovalSnapshot snapshot = new ApprovalSnapshot(id, new MerchantId("shopify-catalog"),
                new SessionId("link:gid://shopify/ProductVariant/1000001"),
                List.of(new SnapshotLine("gid://shopify/ProductVariant/1000001", 1, 30495, "Example Snowboard 1")),
                "USD", 30495, 30495, Instant.parse("2026-01-01T00:10:00Z"), Optional.empty(),
                Optional.of(new LinkSnapshot("Example Shop A", "shop-a.myshopify.example", Optional.empty(),
                        ContinueUrl.parse("https://www.shop-a.example/buy/1000001:1").orElseThrow())));
        repo.insert(new ApprovalRecord("a-bad-link", id, new UserId("alice"), snapshot, snapshot.hash(), "th",
                snapshot.expiresAt(), ApprovalStatus.PENDING, Optional.empty(), Optional.empty()));
        db.write(c -> {
            try (var s = c.prepareStatement("UPDATE approval SET snapshot_json = replace(snapshot_json,"
                    + " 'https://www.shop-a.example/buy/1000001:1', 'https://www.shop-a.example/buy/ 1') WHERE id = 'a-bad-link'")) {
                return s.executeUpdate();
            }
        });

        assertThatThrownBy(() -> repo.findLatest(new UserId("alice"), id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("shop-a").hasMessageNotContaining("buy");
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
