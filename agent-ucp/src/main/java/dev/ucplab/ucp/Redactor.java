package dev.ucplab.ucp;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.MerchantMessage;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Removes credentials from what the merchant or the network sends back before it reaches an outcome, an event or a log
 * (I8, NFR6). It replaces every given secret (the payment token of a {@code complete} body) and the value of an
 * {@code Authorization} header wherever either appears in a cause, an error content or a message. A merchant that echoes
 * the credential back must not be able to put it into the write-ahead log.
 */
final class Redactor {

    static final String MASK = "[redacted]";
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)((?:proxy-)?authorization)(\\s*[:=]\\s*)([^\\s,;\"]+(?:\\s+[^\\s,;\"]+)?)");

    private final List<String> secrets;

    Redactor(Collection<String> secrets) {
        this.secrets = secrets.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    String scrub(String text) {
        String out = text;
        for (String secret : secrets) {
            out = out.replace(secret, MASK);
        }
        return AUTHORIZATION.matcher(out).replaceAll("$1$2" + MASK);
    }

    CallOutcome<CheckoutView> scrub(CallOutcome<CheckoutView> outcome) {
        return switch (outcome) {
            case CallOutcome.NotSent<CheckoutView> n -> changed(n.cause(), scrub(n.cause()))
                    ? new CallOutcome.NotSent<>(scrub(n.cause())) : n;
            case CallOutcome.Uncertain<CheckoutView> u -> changed(u.cause(), scrub(u.cause()))
                    ? new CallOutcome.Uncertain<>(scrub(u.cause())) : u;
            case CallOutcome.ProtocolError<CheckoutView> e -> new CallOutcome.ProtocolError<>(e.httpStatus(),
                    scrub(e.code()), scrub(e.content()), e.retryAfter());
            case CallOutcome.Rejected<CheckoutView> r -> new CallOutcome.Rejected<>(r.httpStatus(), messages(r.messages()));
            case CallOutcome.Ok<CheckoutView> ok -> {
                List<MerchantMessage> messages = messages(ok.messages());
                CheckoutView v = ok.value();
                CheckoutView clean = new CheckoutView(v.id(), v.status(), v.currency(), v.lines(), v.subtotalMinor(),
                        v.taxMinor(), v.totalMinor(), v.order(), v.buyer(), messages);
                yield new CallOutcome.Ok<>(ok.httpStatus(), clean, messages);
            }
        };
    }

    private List<MerchantMessage> messages(List<MerchantMessage> in) {
        return in.stream().map(m -> new MerchantMessage(m.type(), scrub(m.code()), m.path().map(this::scrub),
                scrub(m.content()), m.severity())).toList();
    }

    private static boolean changed(String before, String after) {
        return !Objects.equals(before, after);
    }
}
