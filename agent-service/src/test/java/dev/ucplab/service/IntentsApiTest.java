package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.core.recovery.RecoveryWorker;
import dev.ucplab.persistence.SqlApprovalRepository;
import dev.ucplab.persistence.SqlIntentQueries;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.service.ApiClient.Session;
import dev.ucplab.service.config.ServiceProperties;
import dev.ucplab.service.web.EventCatalogue;
import dev.ucplab.service.web.IntentDriver;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The intents and approvals API (23.2 [CONTRACT], m6-plan sections 3.1 and 3.4, C11) over HTTP against the running
 * service, the reference merchant and the fault proxy. Intents are proposed through the orchestrator bean, as the
 * agent's tool will (C12); everything after that goes through the API.
 */
class IntentsApiTest {

    private static final List<ProposedLine> TWO_SHIRTS = List.of(new ProposedLine("item_123", 2));
    private static final String RATIONALE_CANARY = "CANARY-model-rationale-c11";
    private static final Instant T0 = Instant.parse("2026-11-14T09:00:00Z");

    @TempDir
    Path dir;

    private TestService service;
    private ApiClient api;

    private void start(Optional<Clock> clock, Optional<IntentDriver> driver) throws IOException {
        List<Class<?>> none = List.of();
        service = TestService.start(dir, Map.of(), clock, none, driver);
        api = new ApiClient(service.port());
    }

    private void start() throws IOException {
        start(Optional.empty(), Optional.empty());
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    /** Proposes as {@code user} through the orchestrator bean: AWAITING_APPROVAL with a pending approval. */
    private IntentId propose(String user, List<ProposedLine> lines) {
        Clock clock = service.bean(Clock.class);
        AuthContext auth = new AuthContext(new UserId("local-" + user), user + "@local.ucplab.dev", clock.instant(),
                "test-session");
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(auth, ServiceProperties.MERCHANT,
                lines, RATIONALE_CANARY);
        assertThat(result.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        return result.intentId();
    }

    private IntentId propose(String user) {
        return propose(user, TWO_SHIRTS);
    }

    private String token(Session s, IntentId id) {
        HttpResponse<String> r = api.get(s, "/api/intents/" + id.value() + "/approval");
        assertThat(r.statusCode()).isEqualTo(200);
        return (String) json(r).get("token");
    }

    private HttpResponse<String> decide(Session s, IntentId id, String token, String decision) {
        return api.post(s, "/api/intents/" + id.value() + "/approval",
                "{\"token\":\"" + token + "\",\"decision\":\"" + decision + "\"}");
    }

    private String state(Session s, IntentId id) {
        return (String) json(api.get(s, "/api/intents/" + id.value())).get("state");
    }

    /** Polls the intent through the API until {@code done} holds for its state, for up to 30 s. */
    private String awaitState(Session s, IntentId id, Predicate<String> done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String state = state(s, id);
        while (!done.test(state) && System.nanoTime() < deadline) {
            sleep(25);
            state = state(s, id);
        }
        return state;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> array(HttpResponse<String> r) {
        return (List<Map<String, Object>>) LenientJson.readObject("{\"a\":" + r.body() + "}").get("a");
    }

    // Covers: 23.2, 7.6, I1, I8, m6-plan section 3.4 (an approve through the API reaches COMPLETED through driveNow
    // with no recovery pass, one order; the token appears in no response but the approval GET, headers included)
    @Test
    void approve_completesThroughDriveNow_andTheTokenAppearsNowhereElse() throws IOException {
        start();
        Session alice = api.signIn("alice");
        IntentId id = propose("alice");
        api.get(alice, "/api/intents");
        api.get(alice, "/api/intents/" + id.value());
        String token = token(alice, id);

        HttpResponse<String> approved = decide(alice, id, token, "approve");
        String finalState = awaitState(alice, id, s -> s.equals("COMPLETED"));
        api.get(alice, "/api/intents/" + id.value() + "/events");
        api.get(alice, "/api/intents/" + id.value() + "/approval");
        api.get(alice, "/api/me");

        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(json(approved)).containsEntry("id", id.value()).containsKey("state");
        assertThat(finalState).isEqualTo("COMPLETED");
        assertThat(service.merchant.orders()).hasSize(1);
        assertThat(json(api.get(alice, "/api/intents/" + id.value()))).containsKey("order_id");
        String approvalPath = "/api/intents/" + id.value() + "/approval";
        List<HttpResponse<String>> others = new ArrayList<>(api.seen);
        others.removeIf(r -> r.request().method().equals("GET") && r.uri().getPath().equals(approvalPath)
                && r.statusCode() == 200);
        assertThat(others).hasSizeGreaterThan(8).allSatisfy(r -> {
            assertThat(r.body()).as(r.request().method() + " " + r.uri().getPath()).doesNotContain(token);
            assertThat(r.headers().map().toString()).as("headers of " + r.uri().getPath()).doesNotContain(token);
        });
    }

    // Covers: 23.2, 7.6 (when driveNow starts nothing the answer is still 200; the next recovery pass completes it)
    @Test
    void approve_driveNowStartsNothing_stillAnswers200_andThePassFinishes() throws IOException {
        start(Optional.empty(), Optional.of(id -> false));
        Session alice = api.signIn("alice");
        IntentId id = propose("alice");

        HttpResponse<String> approved = decide(alice, id, token(alice, id), "approve");

        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(json(approved)).containsEntry("state", "APPROVED");
        sleep(200);
        assertThat(state(alice, id)).as("nothing drove it").isEqualTo("APPROVED");
        assertThat(service.bean(RecoveryWorker.class).runOnce()).containsExactly(id);
        assertThat(awaitState(alice, id, s -> s.equals("COMPLETED"))).isEqualTo("COMPLETED");
        assertThat(service.merchant.orders()).hasSize(1);
    }

    // Covers: 23.2 (a reject answers 200 with the state after the decision)
    @Test
    void reject_answers200WithTheState() throws IOException {
        start();
        Session alice = api.signIn("alice");
        IntentId id = propose("alice");

        HttpResponse<String> rejected = decide(alice, id, token(alice, id), "reject");

        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(json(rejected)).containsEntry("state", "REJECTED");
        assertThat(service.merchant.orders()).isEmpty();
    }

    // Covers: T6, I9 (bob gets 404 on each of alice's intent routes, with the same body as for an intent that does not
    // exist; alice's intent is not in bob's list, and alice's cursor does not work for bob)
    @Test
    void anotherUsersIntent_is404LikeAMissingOne() throws IOException {
        start();
        Session alice = api.signIn("alice");
        Session bob = api.signIn("bob");
        IntentId id = propose("alice");
        propose("alice", List.of(new ProposedLine("item_123", 1)));
        String alicesToken = token(alice, id);
        String aliceCursor = (String) json(api.get(alice, "/api/intents?limit=1")).get("next_cursor");

        String[] paths = {"/api/intents/%s", "/api/intents/%s/events", "/api/intents/%s/approval"};
        for (String path : paths) {
            HttpResponse<String> theirs = api.get(bob, path.formatted(id.value()));
            HttpResponse<String> missing = api.get(bob, path.formatted("no-such-intent"));
            assertSameNotFound(path, theirs, missing);
        }
        assertSameNotFound("POST approval", decide(bob, id, alicesToken, "approve"),
                decide(bob, new IntentId("no-such-intent"), alicesToken, "approve"));
        assertThat(json(api.get(bob, "/api/intents"))).containsEntry("items", List.of());
        HttpResponse<String> stolenCursor = api.get(bob, "/api/intents?limit=1&cursor=" + aliceCursor);
        assertThat(stolenCursor.statusCode()).isEqualTo(422);
        assertThat(state(alice, id)).as("bob's approve changed nothing").isEqualTo("AWAITING_APPROVAL");
    }

    private static void assertSameNotFound(String what, HttpResponse<String> theirs, HttpResponse<String> missing) {
        assertThat(theirs.statusCode()).as(what).isEqualTo(404);
        assertThat(missing.statusCode()).as(what).isEqualTo(404);
        Map<String, Object> a = new java.util.HashMap<>(json(theirs));
        Map<String, Object> b = new java.util.HashMap<>(json(missing));
        a.remove("request_id");
        b.remove("request_id");
        assertThat(a).as(what).isEqualTo(b).containsEntry("code", "not_found");
        assertThat(theirs.headers().map().keySet()).as(what).isEqualTo(missing.headers().map().keySet());
    }

    // Covers: 23.2, 9.2 (the quote card on the intent is the newest approval's snapshot, field for field; the approval
    // GET shows the same snapshot with its expiry)
    @Test
    void quoteCard_isTheApprovalSnapshot() throws IOException {
        start();
        Session alice = api.signIn("alice");
        IntentId id = propose("alice");
        ApprovalRecord latest = service.bean(SqlApprovalRepository.class)
                .findLatest(new UserId("local-alice"), id).orElseThrow();

        Map<String, Object> intent = json(api.get(alice, "/api/intents/" + id.value()));
        Map<String, Object> approval = json(api.get(alice, "/api/intents/" + id.value() + "/approval"));

        List<Map<String, Object>> lines = new ArrayList<>();
        for (SnapshotLine l : latest.snapshot().lines()) {
            lines.add(Map.of("item_id", l.itemId(), "quantity", (long) l.quantity(),
                    "unit_price_minor", l.unitPriceMinor()));
        }
        Map<String, Object> expected = Map.of("currency", latest.snapshot().currency(),
                "total_minor", latest.snapshot().totalMinor(), "lines", lines);
        assertThat(intent).containsEntry("id", id.value()).containsEntry("merchant_id", "merchant-a")
                .containsEntry("state", "AWAITING_APPROVAL").containsEntry("quote", expected)
                .containsEntry("approval", Map.of("status", "PENDING",
                        "expires_at", latest.expiresAt().toString()))
                .containsKey("created_at").doesNotContainKeys("order_id", "failure_reason");
        assertThat(approval).containsEntry("snapshot", expected)
                .containsEntry("expires_at", latest.snapshot().expiresAt().toString());
    }

    // Covers: I8, m6-plan section 3.1 (the events route: every type and key a real purchase writes is in the catalogue;
    // the planted rationale reads back as "[redacted]"; seq counts from 1)
    @Test
    void events_passTheAllowlist() throws IOException {
        start();
        Session alice = api.signIn("alice");
        IntentId id = propose("alice");
        decide(alice, id, token(alice, id), "approve");
        awaitState(alice, id, s -> s.equals("COMPLETED"));

        HttpResponse<String> response = api.get(alice, "/api/intents/" + id.value() + "/events");
        List<Map<String, Object>> events = array(response);
        List<IntentEvent> stored = service.bean(SqlIntentQueries.class).events(new UserId("local-alice"), id);

        assertThat(response.body()).doesNotContain(RATIONALE_CANARY);
        assertThat(events).hasSize(stored.size());
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> e = events.get(i);
            assertThat(e).containsEntry("seq", (long) i + 1).containsEntry("type", stored.get(i).type())
                    .containsEntry("at", stored.get(i).at().toString());
        }
        Map<String, Object> proposed = events.stream().filter(e -> e.get("type").equals("proposed")).findFirst()
                .orElseThrow();
        assertThat(proposed.get("payload")).isEqualTo(Map.of("merchant_id", "merchant-a",
                "lines", List.of(Map.of("item_id", "item_123", "quantity", 2L)), "rationale", "[redacted]"));
        for (IntentEvent e : stored) {
            assertThat(EventCatalogue.KEYS).as("type %s", e.type()).containsKey(e.type());
            assertThat(EventCatalogue.KEYS.get(e.type())).as("keys of %s", e.type())
                    .containsAll(LenientJson.readObject(e.payloadJson()).keySet());
        }
    }

    // Covers: 23.2, I3, 9.2 (after a re-quote the old token gives 409 approval_not_pending, and the approval GET shows
    // the new snapshot with a new token, which then completes the purchase)
    @Test
    void requote_oldTokenIsNotPending_newSnapshotIsShown() throws IOException {
        start();
        Session alice = api.signIn("alice");
        IntentId id = propose("alice");
        String oldToken = token(alice, id);
        long oldTotal = ((Number) ((Map<?, ?>) json(api.get(alice, "/api/intents/" + id.value())).get("quote"))
                .get("total_minor")).longValue();
        service.merchant.setPrice("item_123", 2600);

        decide(alice, id, oldToken, "approve");
        String requoted = awaitState(alice, id, s -> s.equals("AWAITING_APPROVAL"));
        HttpResponse<String> stale = decide(alice, id, oldToken, "approve");
        Map<String, Object> fresh = json(api.get(alice, "/api/intents/" + id.value() + "/approval"));

        assertThat(requoted).isEqualTo("AWAITING_APPROVAL");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale)).containsEntry("code", "approval_not_pending");
        assertThat(fresh.get("token")).isNotEqualTo(oldToken);
        Map<?, ?> snapshot = (Map<?, ?>) fresh.get("snapshot");
        assertThat(((Number) snapshot.get("total_minor")).longValue()).isGreaterThan(oldTotal);
        assertThat((List<?>) snapshot.get("lines")).singleElement()
                .satisfies(l -> assertThat(((Number) ((Map<?, ?>) l).get("unit_price_minor")).longValue())
                        .isEqualTo(2600));
        assertThat(decide(alice, id, (String) fresh.get("token"), "approve").statusCode()).isEqualTo(200);
        assertThat(awaitState(alice, id, s -> s.equals("COMPLETED"))).isEqualTo("COMPLETED");
        assertThat(service.merchant.orders()).hasSize(1);
    }

    // Covers: T4, 18.6, m6-plan section 3.4 (fake clock: signed in at t0, the approve at t0 + 16 min is 401
    // reauth_required and changes nothing; after signing in again the same token approves)
    @Test
    void recentSignIn_staleIs401_thenSignInAgainAndRetry() throws IOException {
        FakeClock clock = new FakeClock(T0);
        start(Optional.of(clock), Optional.empty());
        Session first = api.signIn("alice");
        clock.advance(Duration.ofMinutes(15));
        IntentId id = propose("alice");
        String token = token(first, id);
        clock.advance(Duration.ofMinutes(1));

        HttpResponse<String> stale = decide(first, id, token, "approve");
        String unchanged = state(first, id);
        Session again = api.signIn("alice", Optional.of(first.cookie()));
        HttpResponse<String> retried = decide(again, id, token, "approve");

        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(json(stale)).containsEntry("code", "reauth_required");
        assertThat(unchanged).isEqualTo("AWAITING_APPROVAL");
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(awaitState(again, id, s -> s.equals("COMPLETED"))).isEqualTo("COMPLETED");
    }

    // Covers: 23.5 (another worker holding the lease is 409 intent_busy with Retry-After rounded up to whole seconds)
    @Test
    void busy_is409WithRetryAfter() throws IOException {
        FakeClock clock = new FakeClock(T0);
        start(Optional.of(clock), Optional.empty());
        Session alice = api.signIn("alice");
        IntentId id = propose("alice");
        String token = token(alice, id);
        clock.advance(Duration.ofMinutes(1));
        assertThat(service.bean(SqlIntentRepository.class).claim(id, "worker-other", Duration.ofMillis(1500)))
                .isPresent();

        HttpResponse<String> busy = decide(alice, id, token, "approve");

        assertThat(busy.statusCode()).isEqualTo(409);
        assertThat(json(busy)).containsEntry("code", "intent_busy");
        assertThat(busy.headers().firstValue("Retry-After")).contains("2");
        assertThat(state(alice, id)).isEqualTo("AWAITING_APPROVAL");
    }

    // Covers: T1 (a user_id in the approve body is ignored: bob's id in alice's approval, and the decision is alice's)
    @Test
    void userIdInApproveBody_isIgnored() throws IOException {
        start();
        Session alice = api.signIn("alice");
        IntentId id = propose("alice");

        HttpResponse<String> r = api.post(alice, "/api/intents/" + id.value() + "/approval", "{\"token\":\""
                + token(alice, id) + "\",\"decision\":\"reject\",\"user_id\":\"local-bob\",\"decided_by\":\"local-bob\"}");

        assertThat(r.statusCode()).isEqualTo(200);
        ApprovalRecord decided = service.bean(SqlApprovalRepository.class)
                .findLatest(new UserId("local-alice"), id).orElseThrow();
        assertThat(decided.decidedBy()).contains(new UserId("local-alice"));
    }

    // Covers: 23.2, 23.5, 18.5 (a bad approve body is 422; a wrong token is 409 approval_not_pending; the POST needs
    // CSRF)
    @Test
    void approve_badInput() throws IOException {
        start();
        Session alice = api.signIn("alice");
        IntentId id = propose("alice");
        String path = "/api/intents/" + id.value() + "/approval";

        for (String body : List.of("{}", "{\"token\":\"t\"}", "{\"token\":\"t\",\"decision\":\"maybe\"}",
                "{\"token\":7,\"decision\":\"approve\"}", "[]", "not json")) {
            HttpResponse<String> r = api.post(alice, path, body);
            assertThat(r.statusCode()).as(body).isEqualTo(422);
            assertThat(json(r)).as(body).containsEntry("code", "validation_failed");
        }
        HttpResponse<String> wrongToken = decide(alice, id, "v1.not-a-real-token", "approve");
        HttpResponse<String> noCsrf = api.send("POST", path, Optional.of("{\"token\":\"t\",\"decision\":\"approve\"}"),
                "Cookie", "ucplab_session=" + alice.cookie(), "Content-Type", "application/json");

        assertThat(wrongToken.statusCode()).isEqualTo(409);
        assertThat(json(wrongToken)).containsEntry("code", "approval_not_pending");
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        assertThat(state(alice, id)).isEqualTo("AWAITING_APPROVAL");
    }

    // Covers: 23.2, Stage 2 review point 2 (paging: newest first, next_cursor round trip, a state filter; an
    // out-of-range or non-numeric limit, an unknown state and a cursor that does not decode are 422 with a fixed
    // message that never echoes the input)
    @Test
    void list_pagesAndRefusesBadInput() throws IOException {
        start();
        Session alice = api.signIn("alice");
        IntentId first = propose("alice", List.of(new ProposedLine("item_123", 1)));
        IntentId second = propose("alice", List.of(new ProposedLine("item_123", 2)));
        IntentId third = propose("alice", List.of(new ProposedLine("item_123", 3)));
        decide(alice, first, token(alice, first), "reject");

        Map<String, Object> page1 = json(api.get(alice, "/api/intents?limit=2"));
        Map<String, Object> page2 = json(api.get(alice, "/api/intents?limit=2&cursor=" + page1.get("next_cursor")));
        Map<String, Object> rejected = json(api.get(alice, "/api/intents?state=REJECTED"));

        assertThat(ids(page1)).containsExactly(third.value(), second.value());
        assertThat(ids(page2)).containsExactly(first.value());
        assertThat(page2).doesNotContainKey("next_cursor");
        assertThat(ids(rejected)).containsExactly(first.value());
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) page1.get("items")).get(0);
        assertThat(item.keySet()).containsExactlyInAnyOrder("id", "merchant_id", "state", "created_at");

        String otherStateCursor = (String) json(api.get(alice, "/api/intents?limit=1&state=AWAITING_APPROVAL"))
                .get("next_cursor");
        for (String query : List.of("limit=0", "limit=51", "limit=CANARYabc", "limit=-1", "state=CANARY_STATE",
                "state=rejected", "cursor=CANARYgarbage", "cursor=" + otherStateCursor + "&state=REJECTED")) {
            HttpResponse<String> r = api.get(alice, "/api/intents?" + query);
            assertThat(r.statusCode()).as(query).isEqualTo(422);
            assertThat(json(r)).as(query).containsEntry("code", "validation_failed");
            assertThat(r.body()).as(query).doesNotContain("CANARY");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream().map(i -> (String) i.get("id")).toList();
    }
}
