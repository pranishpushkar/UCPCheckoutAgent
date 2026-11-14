package dev.ucplab.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The repository's one lock is released after every call (7.8: never hold a lock longer than the call): a second thread
 * can use the repository right after each of the locking methods, and gives up after two seconds instead of waiting
 * forever. Found by the H3 P5 mutation pass, where removing an {@code unlock()} made a run hang until PIT's timeout.
 */
class InMemoryApprovalRepositoryLockTest {

    private static final Instant T = Instant.parse("2026-11-08T10:10:00Z");
    private static final IntentId INTENT = new IntentId("i-1");

    private final InMemoryApprovalRepository repo = new InMemoryApprovalRepository();
    private final ExecutorService other = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "other-thread");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void stop() {
        other.shutdownNow();
    }

    private static ApprovalRecord approval(String id) {
        ApprovalSnapshot snapshot = new ApprovalSnapshot(INTENT, new MerchantId("m"), new SessionId("s"),
                List.of(new SnapshotLine("item_123", 1, 100)), "USD", 100, 100, T);
        return new ApprovalRecord(id, INTENT, new UserId("alice"), snapshot, snapshot.hash(), "th", T, ApprovalStatus.PENDING,
                Optional.empty(), Optional.empty());
    }

    /** What a second thread sees, within two seconds: a held lock shows as a timeout and fails the test fast. */
    private <T> T fromAnotherThread(Callable<T> call) throws Exception {
        try {
            return other.submit(call).get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the repository's lock is still held after the call returned", e);
        }
    }

    // Covers: 7.8. Kills InMemoryApprovalRepository.insert:29, listForSystem:52 and transition:75 VoidMethodCall (removed
    // ReentrantLock.unlock): after each of them another thread can still read and write
    @Test
    void everyLockingCall_releasesTheLock() throws Exception {
        repo.insert(approval("a-1"));
        assertThat(fromAnotherThread(() -> repo.listForSystem(INTENT))).hasSize(1);

        assertThat(repo.listForSystem(INTENT)).hasSize(1);
        assertThat(fromAnotherThread(() -> repo.findLatestForSystem(INTENT))).isPresent();

        assertThat(repo.transition("a-1", ApprovalStatus.PENDING, ApprovalStatus.EXPIRED, T, Optional.empty())).isTrue();
        assertThat(fromAnotherThread(() -> repo.listForSystem(INTENT).get(0).status())).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(fromAnotherThread(() -> repo.transition("a-1", ApprovalStatus.PENDING, ApprovalStatus.EXPIRED, T,
                Optional.empty()))).as("a repeated transition from the old status is refused, from any thread").isFalse();
    }
}
