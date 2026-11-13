package dev.ucplab.eval.report;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.eval.AutoApprover;
import dev.ucplab.eval.GroundTruth;
import dev.ucplab.eval.RunEvidence;
import dev.ucplab.eval.check.CheckResult;
import dev.ucplab.faultproxy.LogEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes one run's evidence folder (m4-plan section 11): L, O, G, A, D, what the checks found, and the stdout and
 * stderr of each agent child process (S9, S28) as {@code child-<n>.log}. Redaction (I8,
 * m4-plan risk 9) is by construction: only typed fields are written, field by field, and none of them is a request or
 * response body, a payment or approval token (nor a token's hash), an Authorization value or the {@code bodyHmac} key.
 * A frozen request body appears only as its length. The child logs are written as the child printed them: the child
 * prints nothing secret ({@code AgentMain}), and the redaction test searches them like every other file.
 */
public final class EvidenceWriter {

    private EvidenceWriter() {
    }

    /** Writes the folder {@code dir} (created if needed) and returns it. */
    public static Path write(Path dir, RunEvidence e, CheckResult checks) throws IOException {
        Files.createDirectories(dir);
        put(dir, "summary.json", summary(e, checks));
        put(dir, "proxy-log.json", e.proxyLog().stream().map(EvidenceWriter::entry).toList());
        put(dir, "orders.json", e.orders().stream().map(EvidenceWriter::order).toList());
        put(dir, "sessions.json", e.sessions().values().stream().map(EvidenceWriter::session).toList());
        put(dir, "approvals.json", e.approvals().stream().map(EvidenceWriter::decision).toList());
        put(dir, "intents.json", e.intents().stream().map(EvidenceWriter::persisted).toList());
        for (int i = 0; i < e.children().size(); i++) {
            Files.writeString(dir.resolve("child-" + (i + 1) + ".log"), e.children().get(i).log(),
                    StandardCharsets.UTF_8);
        }
        return dir;
    }

    private static void put(Path dir, String name, Object json) throws IOException {
        Files.writeString(dir.resolve(name), JsonOut.write(json), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> summary(RunEvidence e, CheckResult checks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario", e.scenarioId());
        m.put("run", e.run());
        m.put("seed", e.seed());
        m.put("lane", e.lane());
        m.put("user", e.owner().value());
        m.put("aborted", opt(e.aborted()));
        m.put("proposed", e.proposed().stream().map(i -> i.value()).toList());
        m.put("startedAt", e.startedAt().toString());
        m.put("terminalSeenAt", optTime(e.terminalSeenAt()));
        m.put("crashes", e.crashes());
        List<Object> children = new ArrayList<>();
        for (int i = 0; i < e.children().size(); i++) {
            RunEvidence.Child c = e.children().get(i);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("workerId", c.workerId());
            x.put("dbFile", c.dbFile());
            x.put("killed", c.killed());
            x.put("exitValue", c.exitValue());
            x.put("log", "child-" + (i + 1) + ".log");
            children.add(x);
        }
        m.put("children", children);
        Map<String, Object> v = new LinkedHashMap<>();
        checks.byInvariant().forEach((id, list) -> v.put(id, list.stream().map(x -> x.detail()).toList()));
        m.put("violations", v);
        return m;
    }

    private static Map<String, Object> entry(LogEntry l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", l.seq());
        m.put("at", l.at().toString());
        m.put("method", l.method());
        m.put("path", l.path());
        m.put("requestId", opt(l.requestId()));
        m.put("idempotencyKey", opt(l.idempotencyKey()));
        m.put("bodyHmac", opt(l.bodyHmac()));
        m.put("forwards", l.forwards());
        m.put("merchantStatus", l.merchantStatus().isPresent() ? (Object) l.merchantStatus().getAsInt()
                : JsonOut.NULL);
        m.put("action", opt(l.action()));
        m.put("ruleId", opt(l.ruleId()));
        m.put("upstreamError", l.upstreamError());
        m.put("clientGone", l.clientGone());
        m.put("settled", l.settled());
        m.put("rewrite", opt(l.rewrite()));
        return m;
    }

    private static Map<String, Object> order(GroundTruth.Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("checkoutId", o.checkoutId());
        m.put("amountMinor", o.amountMinor());
        return m;
    }

    private static Map<String, Object> session(GroundTruth.Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("status", s.status());
        m.put("currency", s.currency());
        List<Object> lines = new ArrayList<>();
        for (GroundTruth.SessionLine l : s.lines()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("itemId", l.itemId());
            x.put("quantity", l.quantity());
            x.put("unitPriceMinor", l.unitPriceMinor());
            lines.add(x);
        }
        m.put("lines", lines);
        m.put("totalMinor", s.totalMinor());
        m.put("orderId", opt(s.orderId()));
        return m;
    }

    private static Map<String, Object> decision(AutoApprover.Decision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intentId", d.intentId().value());
        m.put("seq", d.seq());
        m.put("approved", d.approved());
        m.put("at", d.at().toString());
        m.put("snapshotHash", d.snapshotHash());
        m.put("snapshot", snapshot(d.snapshot()));
        return m;
    }

    private static Map<String, Object> snapshot(ApprovalSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId", s.merchantId().value());
        m.put("sessionId", s.sessionId().value());
        m.put("lines", s.lines().stream().map(l -> l.itemId() + " x" + l.quantity() + " @" + l.unitPriceMinor())
                .toList());
        m.put("currency", s.currency());
        m.put("totalMinor", s.totalMinor());
        m.put("maxTotalMinor", s.maxTotalMinor());
        m.put("expiresAt", s.expiresAt().toString());
        return m;
    }

    private static Map<String, Object> persisted(RunEvidence.Persisted p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent", intent(p.intent()));
        m.put("attempts", p.attempts().stream().map(EvidenceWriter::attempt).toList());
        m.put("events", p.events().stream().map(EvidenceWriter::event).toList());
        m.put("approvalRows", p.approvalRows().stream().map(EvidenceWriter::approvalRow).toList());
        return m;
    }

    private static Map<String, Object> intent(PurchaseIntent i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id().value());
        m.put("owner", i.owner().value());
        m.put("merchantId", i.merchantId().value());
        m.put("state", i.state().name());
        m.put("version", i.version());
        m.put("sessionId", opt(i.sessionId().map(s -> s.value())));
        m.put("approvedSnapshotHash", opt(i.approvedSnapshotHash()));
        m.put("requoteCount", i.requoteCount());
        m.put("orderId", opt(i.orderId()));
        m.put("failureReason", opt(i.failureReason()));
        m.put("uncertainSince", optTime(i.uncertainSince()));
        m.put("cancelPending", i.cancelPending());
        m.put("createdAt", i.createdAt().toString());
        m.put("updatedAt", i.updatedAt().toString());
        return m;
    }

    /** No request or response body: the frozen body holds the payment credential (I8). Only its length is kept. */
    private static Map<String, Object> attempt(MerchantAttempt a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id().value());
        m.put("op", a.op().name());
        m.put("revision", a.revision());
        m.put("idempotencyKey", opt(a.idempotencyKey().map(k -> k.value())));
        m.put("requestBodyBytes", a.requestBody().<Object>map(b -> (long) b.bytes().length).orElse(JsonOut.NULL));
        m.put("status", a.status().name());
        m.put("httpStatus", a.httpStatus().<Object>map(s -> s).orElse(JsonOut.NULL));
        m.put("sentAt", a.sentAt().toString());
        m.put("respondedAt", optTime(a.respondedAt()));
        return m;
    }

    private static Map<String, Object> event(IntentEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", ev.type());
        m.put("at", ev.at().toString());
        m.put("payload", ev.payloadJson());
        return m;
    }

    /** No token hash: only the decision, its owner and its times. */
    private static Map<String, Object> approvalRow(ApprovalRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("owner", r.owner().value());
        m.put("status", r.status().name());
        m.put("snapshotHash", r.snapshotHash());
        m.put("expiresAt", r.expiresAt().toString());
        m.put("decidedAt", optTime(r.decidedAt()));
        m.put("decidedBy", opt(r.decidedBy().map(u -> u.value())));
        return m;
    }

    private static Object opt(Optional<String> v) {
        return v.<Object>map(x -> x).orElse(JsonOut.NULL);
    }

    private static Object optTime(Optional<Instant> v) {
        return v.<Object>map(Instant::toString).orElse(JsonOut.NULL);
    }
}
