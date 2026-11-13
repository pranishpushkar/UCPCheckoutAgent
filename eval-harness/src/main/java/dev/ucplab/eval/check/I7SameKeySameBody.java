package dev.ucplab.eval.check;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.eval.RunEvidence;
import dev.ucplab.faultproxy.LogEntry;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * I7: a {@code complete} retry under the same approval reuses the same idempotency key and a byte-identical body; a
 * new approval after a re-quote gets a new key. From the wire, with {@code bodyHmac} (equal values under one key mean
 * byte-identical bodies, §12):
 * <ul>
 *   <li>every complete carries a key and a body hash, and all completes under one key carry the same hash;
 *   <li>the approval in force when a complete arrived (the user's last approval before it) maps one-to-one to keys,
 *       and an intent's complete revisions never go back.
 * </ul>
 * A cross-check, not the proof: each body hash in L equals the keyed hash of the agent's own frozen body for that
 * key (the D attempt's {@code request_body}). The body itself is never copied into a violation.
 */
public final class I7SameKeySameBody implements InvariantCheck {

    private static final String HMAC = "HmacSHA256";

    @Override
    public String id() {
        return "I7";
    }

    @Override
    public String description() {
        return "one key and one byte-identical body per approval, a new key for each new approval";
    }

    @Override
    public String evidence() {
        return "L (keys and bodyHmac of every complete) and A (the approval in force); D only as a cross-check that"
                + " L's body hash is the hash of the agent's frozen body";
    }

    @Override
    public List<Violation> check(RunEvidence e, CheckContext c) {
        if (e.aborted().isPresent()) {
            return List.of(Wire.aborted(id(), e));
        }
        Set<String> v = new LinkedHashSet<>();
        Map<String, Wire.KeyOwner> owners = Wire.keyOwners(e);
        Map<String, Set<String>> bodiesByKey = new LinkedHashMap<>();
        Map<String, Set<String>> keysByApproval = new LinkedHashMap<>();
        Map<String, Set<String>> approvalsByKey = new LinkedHashMap<>();
        Map<IntentId, Integer> lastRevision = new LinkedHashMap<>();
        for (LogEntry l : Wire.completes(e)) {
            String where = Wire.describe(l);
            if (l.idempotencyKey().isEmpty()) {
                v.add(where + " has no Idempotency-Key");
                continue;
            }
            String key = l.idempotencyKey().get();
            if (l.bodyHmac().isEmpty()) {
                v.add(where + " (key " + key + ") has no body");
            } else {
                bodiesByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(l.bodyHmac().get());
            }
            Wire.KeyOwner owner = owners.get(key);
            if (owner == null || owner.op() != MutatingOp.COMPLETE) {
                v.add(where + " carries key " + key + ", which is no intent's complete key");
                continue;
            }
            Integer before = lastRevision.put(owner.intent(), owner.revision());
            if (before != null && before > owner.revision()) {
                v.add(where + " went back to complete rev " + owner.revision() + " of " + owner.intent().value()
                        + " after rev " + before);
            }
            String approval = Wire.decisions(e, owner.intent()).stream()
                    .filter(d -> d.approved() && !d.at().isAfter(l.at()))
                    .reduce((a, b) -> b)
                    .map(d -> owner.intent().value() + " approval #" + d.seq())
                    .orElse(owner.intent().value() + " (no approval)");
            keysByApproval.computeIfAbsent(approval, k -> new LinkedHashSet<>()).add(key);
            approvalsByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(approval);
        }
        bodiesByKey.forEach((key, bodies) -> {
            if (bodies.size() > 1) {
                v.add("key " + key + " was sent with " + bodies.size() + " different bodies");
            }
        });
        keysByApproval.forEach((approval, keys) -> {
            if (keys.size() > 1) {
                v.add(approval + " sent complete under " + keys.size() + " keys: " + keys);
            }
        });
        approvalsByKey.forEach((key, approvals) -> {
            if (approvals.size() > 1) {
                v.add("key " + key + " was reused under " + approvals);
            }
        });
        crossCheck(e, c, bodiesByKey, v);
        List<Violation> out = new ArrayList<>();
        v.forEach(s -> out.add(new Violation(id(), s)));
        return out;
    }

    private static void crossCheck(RunEvidence e, CheckContext c, Map<String, Set<String>> bodiesByKey,
                                   Set<String> v) {
        Mac mac = mac(c);
        bodiesByKey.forEach((key, bodies) -> {
            List<MerchantAttempt> attempts = e.intents().stream().flatMap(p -> p.attempts().stream())
                    .filter(a -> a.op() == AttemptOp.COMPLETE
                            && a.idempotencyKey().map(k -> k.value().equals(key)).orElse(false))
                    .toList();
            if (attempts.isEmpty()) {
                v.add("key " + key + ": D holds no complete attempt under it (cross-check)");
                return;
            }
            for (MerchantAttempt a : attempts) {
                Optional<String> stored = a.requestBody().map(b -> HexFormat.of().formatHex(mac.doFinal(b.bytes())));
                if (stored.isEmpty() || !bodies.contains(stored.get())) {
                    v.add("key " + key + ": the body on the wire is not the frozen body of attempt "
                            + a.id().value() + " (cross-check)");
                }
            }
        });
    }

    private static Mac mac(CheckContext c) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(c.hmacKey(), HMAC));
            return mac;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }

    /** For tests: the keyed hash the proxy would log for {@code body}. */
    static String hmacHex(CheckContext c, byte[] body) {
        return HexFormat.of().formatHex(mac(c).doFinal(body));
    }
}
