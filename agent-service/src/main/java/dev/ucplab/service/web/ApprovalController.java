package dev.ucplab.service.web;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StaleVersionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approver channel over HTTP (23.2 [CONTRACT], m6-plan sections 3.1 and 3.4).
 *
 * <p>{@code GET /api/intents/{id}/approval} is the only response that ever carries the approval token: the owner's
 * pending approval, or 404. {@code POST} takes {@code {token, decision}}; owner, recent sign-in (T4) and the token are
 * all checked inside {@code decide}. An approval is driven at once through {@link IntentDriver}; when that starts
 * nothing (another driver has the intent) the answer is still 200, and the next recovery pass finishes the purchase.
 * Anything else in the body, a {@code user_id} included, is ignored (T1).
 */
@RestController
final class ApprovalController {

    static final String BAD_BODY = "token and decision (approve or reject) are required";
    private static final int MAX_TOKEN_LENGTH = 1024;
    private static final System.Logger LOG = System.getLogger(ApprovalController.class.getName());

    private final DefaultApprovalService approvals;
    private final IntentRepository intents;
    private final IntentDriver driver;

    ApprovalController(DefaultApprovalService approvals, IntentRepository intents, IntentDriver driver) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    @GetMapping("/api/intents/{id}/approval")
    Map<String, Object> pending(AuthContext auth, @PathVariable("id") String id) {
        PendingApproval pending = approvals.pending(auth, IntentIds.parse(id))
                .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot", QuoteCard.of(pending.snapshot()));
        body.put("expires_at", pending.snapshot().expiresAt().toString());
        body.put("token", pending.token());
        return body;
    }

    @PostMapping("/api/intents/{id}/approval")
    Map<String, Object> decide(AuthContext auth, @PathVariable("id") String id,
                               @RequestBody Map<String, Object> body) {
        IntentId intentId = IntentIds.parse(id);
        String token = body.get("token") instanceof String t && !t.isBlank() && t.length() <= MAX_TOKEN_LENGTH
                ? t : null;
        Object decided = body.get("decision");
        Optional<Boolean> approve = "approve".equals(decided) ? Optional.of(true)
                : "reject".equals(decided) ? Optional.of(false) : Optional.empty();
        if (token == null || approve.isEmpty()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, BAD_BODY);
        }
        ApprovalDecision decision;
        try {
            decision = approvals.decide(auth, intentId, token, approve.get());
        } catch (RuntimeException e) {
            throw refusal(e).orElseThrow(() -> e);
        }
        Optional<ApiException> refused = refusal(decision);
        if (refused.isPresent()) {
            throw refused.get();
        }
        if (decision instanceof ApprovalDecision.Approved) {
            drive(intentId);
        }
        PurchaseIntent after = intents.find(auth.user(), intentId)
                .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", after.id().value());
        out.put("state", after.state().name());
        return out;
    }

    /** The decision's API error (m6-plan section 3.1's table); empty for {@code Approved} and {@code Rejected}. */
    static Optional<ApiException> refusal(ApprovalDecision decision) {
        return switch (decision) {
            case ApprovalDecision.Approved a -> Optional.empty();
            case ApprovalDecision.Rejected r -> Optional.empty();
            case ApprovalDecision.ReauthRequired r -> Optional.of(ApiException.of(ErrorCode.REAUTH_REQUIRED));
            case ApprovalDecision.NotPending n -> Optional.of(ApiException.of(ErrorCode.APPROVAL_NOT_PENDING));
            case ApprovalDecision.InvalidToken i -> Optional.of(ApiException.of(ErrorCode.APPROVAL_NOT_PENDING));
            case ApprovalDecision.NotFound n -> Optional.of(ApiException.of(ErrorCode.NOT_FOUND));
            case ApprovalDecision.Busy b -> Optional.of(ApiException.busy(b.retryAfter()));
        };
    }

    /** The concurrency exceptions {@code decide} can throw (23.5); empty for anything else, which is a 500. */
    static Optional<ApiException> refusal(RuntimeException e) {
        return switch (e) {
            case LeaseLostException l -> Optional.of(ApiException.of(ErrorCode.INTENT_BUSY));
            case StaleVersionException s -> Optional.of(ApiException.of(ErrorCode.APPROVAL_NOT_PENDING));
            default -> Optional.empty();
        };
    }

    /** The decision is already stored; a failure to start the driver leaves the purchase to the next pass. */
    private void drive(IntentId id) {
        try {
            if (!driver.driveNow(id)) {
                LOG.log(System.Logger.Level.INFO, "approval: driver not started, left to recovery");
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "approval: driver failed to start: {0}", e.getClass().getSimpleName());
        }
    }
}
