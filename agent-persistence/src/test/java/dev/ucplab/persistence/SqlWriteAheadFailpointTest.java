package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash-matrix row 20 (7.3 steps 3 and 5): a failure between the two statements of recordAttempt or resolveAttempt, or
 * just before the commit, rolls the transaction back, so neither the intent change nor the attempt change is visible.
 * The failpoint is a test seam; a JUnit test cannot kill the JVM (the real kill is M4).
 */
class SqlWriteAheadFailpointTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String W1 = "worker-1";

    /** What a crash looks like to the repository: an exception that is not a domain outcome. */
    private static final class SimulatedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(T0);
    private final AtomicReference<String> failAt = new AtomicReference<>();
    private Database db;
    private SqlIntentRepository repo;
    private PurchaseIntent claimed;

    @BeforeEach
    void open() {
        Path file = dir.resolve("failpoint.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        repo = new SqlIntentRepository(db, clock, point -> {
            if (point.equals(failAt.get())) {
                throw new SimulatedFailure();
            }
        });
        repo.insert(PurchaseIntent.proposed(new IntentId("i1"), new UserId("alice"), new MerchantId("shop-1"), "d1",
                List.of(new ProposedLine("item_123", 1)), "a@example.com", T0).withState(IntentState.QUOTING, T0));
        claimed = repo.claimRecoverable(W1, LEASE, 10).get(0);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private MerchantAttempt attempt(String id, AttemptOp op) {
        return MerchantAttempt.sent(new AttemptId(id), claimed.id(), op, 0,
                Optional.of(IdempotencyKey.derive(claimed.id(), MutatingOp.CREATE, 0)),
                Optional.of(new FrozenBody("{\"x\":1}")), clock.instant());
    }

    private PurchaseIntent stored() {
        return repo.findForSystem(claimed.id()).orElseThrow();
    }

    // Covers: 7.3 step 3, crash-matrix row 20 (a failure between the intent update and the attempt insert, or just
    // before the commit, leaves no attempt row and an unchanged intent; the same call then succeeds)
    @Test
    void recordAttempt_rollsBackBothStatements_whenItFailsInside() {
        for (String point : List.of(Failpoint.RECORD_ATTEMPT_BETWEEN, Failpoint.RECORD_ATTEMPT_AFTER)) {
            failAt.set(point);

            assertThatThrownBy(() -> repo.recordAttempt(attempt("a1", AttemptOp.CREATE),
                    claimed.withState(IntentState.AWAITING_APPROVAL, clock.instant()), 0, W1))
                    .as(point).isInstanceOf(SimulatedFailure.class);

            assertThat(repo.attempts(claimed.id())).as(point).isEmpty();
            assertThat(stored().version()).as(point).isZero();
            assertThat(stored().state()).as(point).isEqualTo(IntentState.QUOTING);
            assertThat(db.inUse()).as("the connection went back to the pool").isZero();
        }

        failAt.set(null);
        repo.recordAttempt(attempt("a1", AttemptOp.CREATE), claimed.withState(IntentState.AWAITING_APPROVAL,
                clock.instant()), 0, W1);

        assertThat(repo.attempts(claimed.id())).extracting(a -> a.id().value()).containsExactly("a1");
        assertThat(stored().version()).isEqualTo(1);
    }

    // Covers: 7.3 step 5, crash-matrix row 20 (a failure inside resolveAttempt leaves the attempt SENT and the intent as
    // it was: later recovery treats the attempt as uncertain)
    @Test
    void resolveAttempt_rollsBackBothStatements_whenItFailsInside() {
        repo.recordAttempt(attempt("a1", AttemptOp.CREATE), claimed.withState(IntentState.QUOTING, clock.instant()), 0, W1);
        PurchaseIntent beforeResolve = stored();
        for (String point : List.of(Failpoint.RESOLVE_ATTEMPT_BETWEEN, Failpoint.RESOLVE_ATTEMPT_AFTER)) {
            failAt.set(point);

            assertThatThrownBy(() -> repo.resolveAttempt(new AttemptId("a1"), AttemptStatus.RESPONDED, Optional.of(200),
                    Optional.of("{}"), beforeResolve.withState(IntentState.AWAITING_APPROVAL, clock.instant()), 1, W1))
                    .as(point).isInstanceOf(SimulatedFailure.class);

            MerchantAttempt a = repo.attempts(claimed.id()).get(0);
            assertThat(a.status()).as(point).isEqualTo(AttemptStatus.SENT);
            assertThat(a.httpStatus()).as(point).isEmpty();
            assertThat(a.respondedAt()).as(point).isEmpty();
            assertThat(stored()).as(point).isEqualTo(beforeResolve);
            assertThat(db.inUse()).as("the connection went back to the pool").isZero();
        }

        failAt.set(null);
        repo.resolveAttempt(new AttemptId("a1"), AttemptStatus.RESPONDED, Optional.of(200), Optional.of("{}"),
                beforeResolve.withState(IntentState.AWAITING_APPROVAL, clock.instant()), 1, W1);

        assertThat(repo.attempts(claimed.id()).get(0).status()).isEqualTo(AttemptStatus.RESPONDED);
        assertThat(stored().state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(stored().version()).isEqualTo(2);
    }

    // Covers: I4, 7.3 step 3 (the session check runs inside the transaction: a COMPLETE attempt recorded together with a
    // session change is refused whole, so neither the attempt nor the new session is stored)
    @Test
    void recordAttempt_refusesSessionChangeTogetherWithCompleteAttempt_andStoresNothing() {
        repo.update(claimed.withSessionId(new SessionId("s1")), 0, W1);
        PurchaseIntent withSession = stored();

        assertThatThrownBy(() -> repo.recordAttempt(attempt("c1", AttemptOp.COMPLETE),
                withSession.withSessionId(new SessionId("s2")).withState(IntentState.COMPLETING, clock.instant()), 1, W1))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repo.attempts(claimed.id())).isEmpty();
        assertThat(stored().sessionId()).contains(new SessionId("s1"));
        assertThat(stored().version()).isEqualTo(1);
    }
}
