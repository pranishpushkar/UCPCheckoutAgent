package dev.ucplab.service.cli;

import dev.ucplab.service.cli.ApiSession.Reply;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The command line client (m6-plan section 8, FR3, FR5): it talks to the REST API, never to core, so it passes through
 * the same session, CSRF and tenancy checks the console does. Every invocation signs in, does its work and signs out;
 * it writes no cookie, token or session to disk. The approval token is read and sent inside one process and is never
 * printed. It uses the JDK and {@code LenientJson} only, so {@code scripts/ucplab} runs it without Spring.
 *
 * <pre>
 * ucplab [--user NAME] [--base-url URL] COMMAND ...
 *   run "message"             start a run
 *   answer RUN "message"      answer a question, or continue a run
 *   run-status RUN
 *   intents [--state STATE]   intent ID | events ID | approval ID | approve ID | reject ID
 *   wait INTENT [--timeout 60s]
 *   lab faults PRESET|clear | lab orders | lab reset
 * </pre>
 *
 * Exit codes: 0 done, 1 the service refused or the thing was not found, 2 a usage error, 3 a wait timed out.
 */
public final class Cli {

    static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "REJECTED", "FAILED_NOT_CHARGED", "ESCALATED");
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(300);
    private static final String USAGE = String.join("\n",
            "usage: ucplab [--user NAME] [--base-url URL] COMMAND",
            "  run MESSAGE | answer RUN MESSAGE | run-status RUN",
            "  intents [--state STATE] | intent ID | events ID | approval ID | approve ID | reject ID",
            "  wait INTENT [--timeout 60s]",
            "  lab faults PRESET|clear | lab orders | lab reset");

    private final PrintStream out;
    private final PrintStream err;
    private final Consumer<String> secrets;

    private Cli(PrintStream out, PrintStream err, Consumer<String> secrets) {
        this.out = out;
        this.err = err;
        this.secrets = secrets;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, s -> { }));
    }

    /**
     * Runs one invocation and returns its exit code.
     *
     * @param secrets told each secret the invocation handles (the cookie, the CSRF token, the approval token), so a test
     *     can search what was printed for them; the program passes a function that ignores them
     */
    public static int run(String[] args, PrintStream out, PrintStream err, Consumer<String> secrets) {
        return new Cli(out, err, secrets).execute(List.of(args));
    }

    private int execute(List<String> args) {
        String user = "alice";
        String baseUrl = DEFAULT_BASE_URL;
        int i = 0;
        while (i < args.size() && args.get(i).startsWith("--")) {
            if (i + 1 >= args.size()) {
                return usage("missing a value for " + args.get(i));
            }
            switch (args.get(i)) {
                case "--user" -> user = args.get(i + 1);
                case "--base-url" -> baseUrl = args.get(i + 1);
                default -> {
                    return usage("unknown option " + args.get(i));
                }
            }
            i += 2;
        }
        if (i >= args.size()) {
            return usage("no command");
        }
        String command = args.get(i);
        List<String> rest = args.subList(i + 1, args.size());
        if (!known(command)) {
            return usage("unknown command " + command);
        }
        try (ApiSession api = new ApiSession(baseUrl, secrets)) {
            api.signIn(user);
            return dispatch(api, command, rest);
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("error: interrupted");
            return 1;
        } catch (UsageError e) {
            return usage(e.getMessage());
        }
    }

    private static boolean known(String command) {
        return Set.of("run", "answer", "run-status", "intents", "intent", "events", "approval", "approve", "reject",
                "wait", "lab").contains(command);
    }

    private int dispatch(ApiSession api, String command, List<String> a) throws IOException, InterruptedException {
        return switch (command) {
            case "run" -> run(api, one(a, "a message"));
            case "answer" -> answer(api, arg(a, 0, "a run id"), arg(a, 1, "a message"));
            case "run-status" -> showRun(api, api.get("/api/runs/" + id(arg(a, 0, "a run id"))));
            case "intents" -> intents(api, a);
            case "intent" -> intent(api, arg(a, 0, "an intent id"));
            case "events" -> events(api, arg(a, 0, "an intent id"));
            case "approval" -> approval(api, arg(a, 0, "an intent id"));
            case "approve" -> decide(api, arg(a, 0, "an intent id"), true);
            case "reject" -> decide(api, arg(a, 0, "an intent id"), false);
            case "wait" -> await(api, a);
            default -> lab(api, a);
        };
    }

    // ---- runs --------------------------------------------------------------------------------------------------

    private int run(ApiSession api, String message) throws IOException, InterruptedException {
        return settle(api, api.post("/api/runs", "{\"message\":" + ApiSession.quote(message) + "}"));
    }

    private int answer(ApiSession api, String run, String message) throws IOException, InterruptedException {
        return settle(api, api.post("/api/runs/" + id(run) + "/messages",
                "{\"message\":" + ApiSession.quote(message) + "}"));
    }

    /** Prints a run's reply; a run still {@code running} is read again until it settles or a minute has passed. */
    private int settle(ApiSession api, Reply reply) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + DEFAULT_WAIT.toNanos();
        Reply now = reply;
        while (now.ok() && "running".equals(now.string("status")) && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis());
            now = api.get("/api/runs/" + id(now.string("run_id")));
        }
        return showRun(api, now);
    }

    private int showRun(ApiSession api, Reply reply) {
        if (!reply.ok()) {
            return refused(reply);
        }
        out.println("run " + reply.string("run_id") + " " + reply.string("status")
                + (reply.string("reason").isEmpty() ? "" : " (" + reply.string("reason") + ")"));
        for (Object m : list(reply.body().get("messages"))) {
            if (m instanceof Map<?, ?> message) {
                out.println("  " + message.get("role") + ": " + message.get("text"));
            }
        }
        for (Object e : list(reply.body().get("tool_events"))) {
            if (e instanceof Map<?, ?> event) {
                out.println("  tool " + event.get("tool") + ": " + event.get("summary"));
            }
        }
        for (Object intent : list(reply.body().get("intent_ids"))) {
            out.println("  intent " + intent);
        }
        return 0;
    }

    // ---- intents and approvals ---------------------------------------------------------------------------------

    private int intents(ApiSession api, List<String> a) throws IOException, InterruptedException {
        String query = "";
        if (a.size() >= 2 && a.get(0).equals("--state")) {
            query = "?state=" + id(a.get(1));
        } else if (!a.isEmpty()) {
            throw new UsageError("intents takes only --state STATE");
        }
        Reply reply = api.get("/api/intents" + query);
        if (!reply.ok()) {
            return refused(reply);
        }
        for (Object item : list(reply.body().get("items"))) {
            if (item instanceof Map<?, ?> i) {
                out.println(i.get("id") + " " + i.get("state") + " " + i.get("merchant_id") + " " + i.get("created_at"));
            }
        }
        return 0;
    }

    private int intent(ApiSession api, String id) throws IOException, InterruptedException {
        Reply reply = api.get("/api/intents/" + id(id));
        if (!reply.ok()) {
            return refused(reply);
        }
        out.println("intent " + reply.string("id") + " " + reply.string("state"));
        out.println("  merchant " + reply.string("merchant_id"));
        if (!reply.string("order_id").isEmpty()) {
            out.println("  order " + reply.string("order_id"));
        }
        if (!reply.string("failure_reason").isEmpty()) {
            out.println("  failure " + reply.string("failure_reason"));
        }
        if (reply.body().get("quote") instanceof Map<?, ?> quote) {
            out.println("  total " + money(quote.get("total_minor"), String.valueOf(quote.get("currency"))));
        }
        return 0;
    }

    private int events(ApiSession api, String id) throws IOException, InterruptedException {
        Reply reply = api.get("/api/intents/" + id(id) + "/events");
        if (!reply.ok()) {
            return refused(reply);
        }
        // The events route answers a JSON array, which the object reader does not take: read it from the raw body.
        for (Object e : list(reply.body().get("items"))) {
            out.println(e);
        }
        return 0;
    }

    /** Prints what the card would show, from the snapshot only. Never the token. */
    private int approval(ApiSession api, String id) throws IOException, InterruptedException {
        Reply reply = api.get("/api/intents/" + id(id) + "/approval");
        if (!reply.ok()) {
            return refused(reply);
        }
        if (reply.body().get("token") instanceof String token) {
            secrets.accept(token);
        }
        printCard(reply);
        return 0;
    }

    private void printCard(Reply reply) {
        Map<?, ?> snapshot = reply.body().get("snapshot") instanceof Map<?, ?> s ? s : Map.of();
        String currency = String.valueOf(snapshot.get("currency"));
        long lines = 0;
        for (Object o : list(snapshot.get("lines"))) {
            if (o instanceof Map<?, ?> line) {
                long quantity = number(line.get("quantity"));
                long unit = number(line.get("unit_price_minor"));
                lines += quantity * unit;
                out.println("  " + line.get("item_id") + " x" + quantity + " @ " + money(unit, currency));
            }
        }
        long total = number(snapshot.get("total_minor"));
        out.println("  other charges (tax) " + money(total - lines, currency));
        out.println("  total " + money(total, currency));
        out.println("  expires " + reply.string("expires_at"));
        out.println("  price and stock are not held");
    }

    /** Reads the approval and sends the decision in this one process; the token is never printed. */
    private int decide(ApiSession api, String id, boolean approve) throws IOException, InterruptedException {
        Reply pending = api.get("/api/intents/" + id(id) + "/approval");
        if (!pending.ok()) {
            return refused(pending);
        }
        String token = pending.string("token");
        secrets.accept(token);
        Reply reply = api.post("/api/intents/" + id(id) + "/approval",
                "{\"token\":" + ApiSession.quote(token) + ",\"decision\":\"" + (approve ? "approve" : "reject") + "\"}");
        if (!reply.ok()) {
            return refused(reply);
        }
        out.println("intent " + reply.string("id") + " " + reply.string("state"));
        return 0;
    }

    private int await(ApiSession api, List<String> a) throws IOException, InterruptedException {
        String id = arg(a, 0, "an intent id");
        Duration timeout = DEFAULT_WAIT;
        if (a.size() >= 3 && a.get(1).equals("--timeout")) {
            timeout = duration(a.get(2));
        } else if (a.size() != 1) {
            throw new UsageError("wait takes an intent id and an optional --timeout 60s");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Reply reply = api.get("/api/intents/" + id(id));
            if (!reply.ok()) {
                return refused(reply);
            }
            String state = reply.string("state");
            if (TERMINAL.contains(state)) {
                out.println("intent " + id + " " + state);
                return 0;
            }
            if (System.nanoTime() > deadline) {
                err.println("error: intent " + id + " is still " + state + " after " + timeout.toSeconds() + " s");
                return 3;
            }
            Thread.sleep(POLL.toMillis());
        }
    }

    // ---- the lab -------------------------------------------------------------------------------------------------

    private int lab(ApiSession api, List<String> a) throws IOException, InterruptedException {
        String what = arg(a, 0, "faults, orders or reset");
        switch (what) {
            case "faults" -> {
                String preset = arg(a, 1, "a preset or clear");
                Reply reply = preset.equals("clear") ? api.delete("/api/lab/faults")
                        : api.put("/api/lab/faults", "{\"preset\":" + ApiSession.quote(preset) + "}");
                if (!reply.ok()) {
                    return refused(reply);
                }
                out.println(preset.equals("clear") ? "faults cleared" : "fault set: " + preset);
                return 0;
            }
            case "orders" -> {
                Reply reply = api.get("/api/lab/orders?merchant_id=merchant-a");
                if (!reply.ok()) {
                    return refused(reply);
                }
                out.println("orders " + list(reply.body().get("orders")).size());
                return 0;
            }
            case "reset" -> {
                Reply reply = api.post("/api/lab/reset", "{}");
                if (!reply.ok()) {
                    return refused(reply);
                }
                out.println("lab reset");
                return 0;
            }
            default -> throw new UsageError("lab takes faults, orders or reset");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The service's refusal, as its fixed message: never a body, a header or a secret. */
    private int refused(Reply reply) {
        String message = switch (reply.status()) {
            case 404 -> "not found";
            case 401 -> "not signed in";
            default -> reply.string("message").isEmpty() ? "refused (" + reply.status() + ")"
                    : reply.string("message") + " (" + reply.string("code") + ")";
        };
        err.println("error: " + message);
        return 1;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String money(Object minor, String currency) {
        long m = number(minor);
        String sign = m < 0 ? "-" : "";
        long abs = Math.abs(m);
        return sign + ("USD".equals(currency) ? "$" : currency + " ") + abs / 100 + "." + String.format("%02d", abs % 100);
    }

    private static Duration duration(String text) {
        try {
            if (text.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(text));
        } catch (NumberFormatException e) {
            throw new UsageError("a timeout looks like 60s or 2m");
        }
    }

    /** An id goes into a path: only the characters the service's ids have. */
    private static String id(String text) {
        if (!text.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new UsageError("not an id: it holds characters an id does not");
        }
        return text;
    }

    private static String one(List<String> a, String what) {
        if (a.size() != 1) {
            throw new UsageError("this command takes " + what);
        }
        return a.getFirst();
    }

    private static String arg(List<String> a, int index, String what) {
        if (a.size() <= index) {
            throw new UsageError("missing " + what);
        }
        return a.get(index);
    }

    private int usage(String problem) {
        err.println("error: " + problem);
        err.println(USAGE);
        return 2;
    }

    private static final class UsageError extends RuntimeException {
        UsageError(String message) {
            super(message);
        }
    }
}
