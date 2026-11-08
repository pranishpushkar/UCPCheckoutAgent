package dev.ucplab.core.approval;

/**
 * The {@code approval.status} column (section 10). PENDING until the owner decides or it expires; APPROVED when the
 * owner approves (the intent moves to APPROVED and the approval can no longer be decided, 9.2 single use); CONSUMED
 * once the {@code complete} request under it has been written ahead (7.3).
 */
public enum ApprovalStatus {
    PENDING, APPROVED, REJECTED, EXPIRED, CONSUMED
}
