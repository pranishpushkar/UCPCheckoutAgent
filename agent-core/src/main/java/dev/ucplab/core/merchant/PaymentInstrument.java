package dev.ucplab.core.merchant;

import java.util.Objects;

/**
 * The payment instrument put in the {@code complete} body (A.2): {@code {"id", "handler_id", "type",
 * "credential": {"type": "MOCK", "token"}}}. The token is a credential, so {@link #toString()} leaves it out (I8).
 */
public record PaymentInstrument(String id, String handlerId, String type, String credentialType, String token) {

    public PaymentInstrument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handlerId, "handlerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(credentialType, "credentialType");
        Objects.requireNonNull(token, "token");
    }

    /** A mock card for the lab merchant's {@code mock_pay_1} handler (A.2). */
    public static PaymentInstrument mockCard(String id, String token) {
        return new PaymentInstrument(id, "mock_pay_1", "mock_card", "MOCK", token);
    }

    @Override
    public String toString() {
        return "PaymentInstrument[id=" + id + ", handlerId=" + handlerId + ", type=" + type + ", token=redacted]";
    }
}
