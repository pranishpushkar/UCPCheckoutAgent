package dev.ucplab.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.StaleVersionException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The decision mapping of m6-plan section 3.1, for every {@link ApprovalDecision} the code has (23.2, 23.5, D3). */
class ApprovalMappingTest {

    private static final IntentId ID = new IntentId("i-1");

    /** One instance of each permitted decision, and what the API answers. */
    private static final Map<ApprovalDecision, Optional<ErrorCode>> EXPECTED = Map.of(
            new ApprovalDecision.Approved(ID), Optional.empty(),
            new ApprovalDecision.Rejected(ID), Optional.empty(),
            new ApprovalDecision.ReauthRequired(), Optional.of(ErrorCode.REAUTH_REQUIRED),
            new ApprovalDecision.NotPending(), Optional.of(ErrorCode.APPROVAL_NOT_PENDING),
            new ApprovalDecision.InvalidToken(), Optional.of(ErrorCode.APPROVAL_NOT_PENDING),
            new ApprovalDecision.NotFound(), Optional.of(ErrorCode.NOT_FOUND),
            new ApprovalDecision.Busy(Duration.ofMillis(1200)), Optional.of(ErrorCode.INTENT_BUSY));

    // Covers: 23.2, 23.5, D3 (every decision type the code declares is mapped; the list of types is read from the
    // sealed interface, so a new decision fails this test until it is mapped)
    @Test
    void everyDecision_mapsAsTheTableSays() {
        List<Class<?>> declared = Arrays.asList(ApprovalDecision.class.getPermittedSubclasses());
        assertThat(EXPECTED.keySet().stream().map(Object::getClass).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(declared);

        EXPECTED.forEach((decision, code) -> {
            Optional<ApiException> refusal = ApprovalController.refusal(decision);
            assertThat(refusal.map(ApiException::code)).as(decision.toString()).isEqualTo(code);
        });
        assertThat(ErrorCode.REAUTH_REQUIRED.status()).isEqualTo(401);
        assertThat(ErrorCode.APPROVAL_NOT_PENDING.status()).isEqualTo(409);
        assertThat(ErrorCode.INTENT_BUSY.status()).isEqualTo(409);
    }

    // Covers: 23.5 (Busy carries Retry-After, its duration rounded up to whole seconds)
    @Test
    void busy_retryAfter_isRoundedUpToWholeSeconds() {
        assertThat(ApprovalController.refusal(new ApprovalDecision.Busy(Duration.ofMillis(1200))).orElseThrow()
                .retryAfter()).contains(Duration.ofMillis(1200));
        assertThat(ApiErrors.retryAfterSeconds(Duration.ofMillis(1200))).isEqualTo(2);
        assertThat(ApiErrors.retryAfterSeconds(Duration.ofSeconds(2))).isEqualTo(2);
        assertThat(ApiErrors.retryAfterSeconds(Duration.ofNanos(1))).isEqualTo(1);
        assertThat(ApiErrors.retryAfterSeconds(Duration.ZERO)).isZero();
    }

    // Covers: 23.5 (LeaseLostException is 409 intent_busy and StaleVersionException 409 approval_not_pending; anything
    // else is not mapped, so it becomes a 500)
    @Test
    void concurrencyExceptions_areMapped() {
        assertThat(ApprovalController.refusal(new LeaseLostException(ID, "worker-x")).map(ApiException::code))
                .contains(ErrorCode.INTENT_BUSY);
        assertThat(ApprovalController.refusal(new StaleVersionException(ID, 3, 4)).map(ApiException::code))
                .contains(ErrorCode.APPROVAL_NOT_PENDING);
        assertThat(ApprovalController.refusal(new IllegalStateException("x"))).isEmpty();
    }
}
