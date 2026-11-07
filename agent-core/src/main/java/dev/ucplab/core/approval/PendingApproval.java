package dev.ucplab.core.approval;

import java.util.Objects;

/**
 * An approval waiting for the user's decision. The token goes to the approver channel only; it is never in a tool
 * result or a prompt (I6, I8), so {@link #toString()} leaves it out. Appendix B names this type without fields.
 */
public record PendingApproval(ApprovalSnapshot snapshot, String snapshotHash, String token) {

    public PendingApproval {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(snapshotHash, "snapshotHash");
        Objects.requireNonNull(token, "token");
    }

    @Override
    public String toString() {
        return "PendingApproval[intentId=" + snapshot.intentId() + ", snapshotHash=" + snapshotHash + ']';
    }
}
