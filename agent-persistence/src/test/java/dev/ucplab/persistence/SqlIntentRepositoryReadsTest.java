package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.DuplicateIntentException;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the SQL adapter adds to the shared contract: storage fidelity, the event sequence and attempt order. */
class SqlIntentRepositoryReadsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final MerchantId SHOP = new MerchantId("shop-1");

    @TempDir
    Path dir;

    private Database db;
    private SqlIntentRepository repo;

    @BeforeEach
    void open() {
        Path file = dir.resolve("reads.db");
        Migrations.migrate(file);
        db = Database.open(file, 8);
        repo = new SqlIntentRepository(db, new MutableClock(T0));
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static PurchaseIntent proposed(String id, UserId owner, String key) {
        return PurchaseIntent.proposed(new IntentId(id), owner, SHOP, key,
                List.of(new ProposedLine("item_456", 3), new ProposedLine("item_123", 1)), "a@example.com", T0);
    }

    // Covers: 7.3, 7.5, 7.6, 10 (every column survives a write and a read: lines in their order, optional fields,
    // nanosecond times, the cancel flag and the lease)
    @Test
    void insertThenFind_roundTripsEveryField() {
        PurchaseIntent full = new PurchaseIntent(
                new IntentId("i1"), ALICE, SHOP, "k1",
                List.of(new ProposedLine("item_456", 3), new ProposedLine("item_123", 1)), "a@example.com",
                IntentState.RECONCILING, 7, Optional.of(new SessionId("s1")), Optional.of("hash"), 2,
                Optional.of("order-9"), Optional.of("why"), Optional.of(T0.plusNanos(5)), true,
                Optional.of("worker-1"), Optional.of(T0.plusSeconds(30)), T0, T0.plusMillis(250));

        repo.insert(full);

        assertThat(repo.find(ALICE, full.id())).contains(full);
        assertThat(repo.findForSystem(full.id())).contains(full);
    }

    // Covers: T2, T6 (the owner filter is in the SQL: another user's read of an existing id is empty)
    @Test
    void find_doesNotRevealAnotherUsersIntent() {
        repo.insert(proposed("i1", ALICE, "k1"));

        assertThat(repo.find(BOB, new IntentId("i1"))).isEmpty();
        assertThat(repo.find(ALICE, new IntentId("i1"))).isPresent();
    }

    // Covers: 7.7 (findActiveByDedupeKey ignores a terminal intent that still carries the key)
    @Test
    void findActiveByDedupeKey_ignoresTerminalIntents() {
        for (IntentState s : IntentState.values()) {
            PurchaseIntent moved = proposed("i-" + s, ALICE, "k-" + s).withState(s, T0);
            repo.insert(s == IntentState.COMPLETED ? moved.withOrderId("order-1") : moved);
            assertThat(repo.findActiveByDedupeKey("k-" + s).isPresent()).as(s.name()).isEqualTo(!s.isTerminal());
        }
    }

    // Covers: 7.7 (the partial unique index refuses a second active intent with the key, from any user)
    @Test
    void insert_refusesSecondActiveIntentWithSameKey_whoeverOwnsIt() {
        repo.insert(proposed("i1", ALICE, "k1"));

        assertThatThrownBy(() -> repo.insert(proposed("i2", BOB, "k1"))).isInstanceOf(DuplicateIntentException.class);
        assertThat(repo.findForSystem(new IntentId("i2"))).isEmpty();
    }

    // Covers: 7.3 (event sequence numbers are MAX + 1 per intent, so they run 1, 2, 3 for each intent separately)
    @Test
    void appendEvent_numbersEventsPerIntent() {
        repo.insert(proposed("i1", ALICE, "k1"));
        repo.insert(proposed("i2", ALICE, "k2"));

        repo.appendEvent(new IntentId("i1"), new IntentEvent("a", "{}", T0));
        repo.appendEvent(new IntentId("i2"), new IntentEvent("b", "{}", T0));
        repo.appendEvent(new IntentId("i1"), new IntentEvent("c", "{}", T0));

        List<String> seqs = db.read(c -> {
            List<String> out = new ArrayList<>();
            try (var s = c.createStatement();
                    var r = s.executeQuery("SELECT intent_id || ':' || seq FROM intent_event ORDER BY intent_id, seq")) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
            return out;
        });
        assertThat(seqs).containsExactly("i1:1", "i1:2", "i2:1");
    }

    // Covers: 7.3, 7.8 (concurrent appends to one intent each get their own seq: none lost, none refused)
    @Test
    void appendEvent_givesConcurrentAppendersDistinctSequenceNumbers() throws Exception {
        repo.insert(proposed("i1", ALICE, "k1"));
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String type = "e" + i;
                done.add(pool.submit(() -> {
                    start.await();
                    repo.appendEvent(new IntentId("i1"), new IntentEvent(type, "{}", T0));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<IntentEvent> events = repo.events(new IntentId("i1"));
        assertThat(events).hasSize(writers);
        assertThat(events.stream().map(IntentEvent::type).distinct().count()).isEqualTo(writers);
    }

    private void insertAttempt(String id, String intent, int seq, String status) {
        db.write(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO merchant_attempt (id, intent_id, seq, op, revision, idempotency_key, request_body, status,"
                            + " http_status, response_json, sent_at, responded_at) VALUES (?, ?, ?, 'COMPLETE', 1, ?, ?, ?,"
                            + " ?, ?, ?, ?)")) {
                s.setString(1, id);
                s.setString(2, intent);
                s.setInt(3, seq);
                s.setString(4, "key-" + id);
                s.setString(5, "{\"x\":1}");
                s.setString(6, status);
                if (status.equals("RESPONDED")) {
                    s.setInt(7, 200);
                    s.setString(8, "{\"ok\":true}");
                    s.setString(10, SqlTimes.format(T0.plusSeconds(1)));
                } else {
                    s.setNull(7, Types.INTEGER);
                    s.setNull(8, Types.VARCHAR);
                    s.setNull(10, Types.VARCHAR);
                }
                s.setString(9, SqlTimes.format(T0));
                s.executeUpdate();
            }
            return null;
        });
    }

    // Covers: 7.3 step 1, 7.4 (attempts come back in the per-intent seq order, not id order, and only the intent's own;
    // the frozen body, key and outcome columns read back)
    @Test
    void attempts_areOrderedBySeq_perIntent() {
        repo.insert(proposed("i1", ALICE, "k1"));
        repo.insert(proposed("i2", ALICE, "k2"));
        insertAttempt("z-first", "i1", 1, "RESPONDED");
        insertAttempt("a-second", "i1", 2, "SENT");
        insertAttempt("other", "i2", 1, "SENT");

        List<MerchantAttempt> attempts = repo.attempts(new IntentId("i1"));

        assertThat(attempts).extracting(a -> a.id().value()).containsExactly("z-first", "a-second");
        MerchantAttempt first = attempts.get(0);
        assertThat(first.op()).isEqualTo(AttemptOp.COMPLETE);
        assertThat(first.status()).isEqualTo(AttemptStatus.RESPONDED);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.idempotencyKey()).contains(new IdempotencyKey("key-z-first"));
        assertThat(first.requestBody()).contains(new FrozenBody("{\"x\":1}"));
        assertThat(first.httpStatus()).contains(200);
        assertThat(first.responseJson()).contains("{\"ok\":true}");
        assertThat(first.sentAt()).isEqualTo(T0);
        assertThat(first.respondedAt()).contains(T0.plusSeconds(1));
        MerchantAttempt second = attempts.get(1);
        assertThat(second.httpStatus()).isEmpty();
        assertThat(second.responseJson()).isEmpty();
        assertThat(second.respondedAt()).isEmpty();
        assertThat(repo.attempts(new IntentId("none"))).isEmpty();
    }
}
