package dev.ucplab.ucp.mcp;

import static dev.ucplab.ucp.mcp.McpTransportTest.RPC_ID;
import static dev.ucplab.ucp.mcp.McpTransportTest.call;
import static dev.ucplab.ucp.mcp.McpTransportTest.createAnswer;
import static dev.ucplab.ucp.mcp.McpTransportTest.createBody;
import static dev.ucplab.ucp.mcp.McpTransportTest.executor;
import static dev.ucplab.ucp.mcp.McpTransportTest.reply;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * {@code result.content[].text} and a JSON-RPC error's {@code message} and {@code data} are merchant text (8.4, I8): a
 * canary placed there reaches no log, no stream and no part of the outcome. agent-ucp logs nothing itself, so the test
 * captures java.util.logging (the JDK's HTTP client logs there) and standard out and error while full calls run through
 * the retry executor, then looks at every outcome and its {@code toString}.
 */
class McpCanaryTest {

    private static final String CANARY = "CANARY-mcp-text-7f3e1a";

    // Covers: I8, 8.4 (content[].text is never read or logged; error message and data never reach an outcome)
    @Test
    void merchantText_reachesNoLog_noStream_andNoOutcome() {
        String okWithCanaryText = createAnswer().replaceFirst("\"text\":\"", "\"text\":\"" + CANARY);
        List<String> answers = List.of(
                okWithCanaryText,
                okWithCanaryText.replace("\"isError\":false", "\"isError\":true"),
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\",\"error\":{\"code\":-32000,\"message\":\"" + CANARY
                        + "\",\"data\":\"" + CANARY + "\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\",\"error\":{\"code\":-32000,\"message\":\"AuthenticationFailed\","
                        + "\"data\":\"" + CANARY + "\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + CANARY
                        + "\"}],\"isError\":false}}");
        List<String> seen = new ArrayList<>();
        Logger root = Logger.getLogger("");
        Level before = root.getLevel();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                seen.add(r.getMessage() + " " + r.getThrown());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        ByteArrayOutputStream streams = new ByteArrayOutputStream();
        PrintStream out = System.out;
        PrintStream err = System.err;
        root.addHandler(capture);
        root.setLevel(Level.ALL);
        System.setOut(new PrintStream(streams, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(streams, true, StandardCharsets.UTF_8));
        try {
            for (String answer : answers) {
                for (int status : new int[] {200, 422}) {
                    for (AttemptOp op : List.of(AttemptOp.CREATE, AttemptOp.GET)) {
                        McpTransportTest.Scripted inner = new McpTransportTest.Scripted();
                        for (int i = 0; i < 4; i++) {
                            inner.then(reply(status, answer));
                        }
                        CallOutcome<CheckoutView> outcome = executor(inner, new ArrayList<>()).execute(call(op,
                                op == AttemptOp.GET ? McpEnvelope.toolCall(() -> RPC_ID, "get_checkout",
                                        java.util.Map.of("id", "x"), McpTransportTest.PROFILE, java.util.Optional.empty())
                                        : createBody(() -> RPC_ID)));
                        seen.add(outcome.toString());
                        seen.add(String.valueOf(describe(outcome)));
                    }
                }
            }
        } finally {
            System.setOut(out);
            System.setErr(err);
            root.removeHandler(capture);
            root.setLevel(before);
        }
        seen.add(streams.toString(StandardCharsets.UTF_8));

        // Case-insensitive: a code is lower-cased, and a lower-cased canary is still a leak.
        assertThat(String.join("\n", seen)).doesNotContainIgnoringCase(CANARY);
        assertThat(seen).as("the calls ran").hasSizeGreaterThan(20);
    }

    /** Every text field an outcome carries. */
    private static String describe(CallOutcome<CheckoutView> outcome) {
        return switch (outcome) {
            case CallOutcome.Ok<CheckoutView> ok -> ok.value() + " " + ok.messages();
            case CallOutcome.Rejected<CheckoutView> r -> String.valueOf(r.messages());
            case CallOutcome.NotSent<CheckoutView> n -> n.cause();
            case CallOutcome.Uncertain<CheckoutView> u -> u.cause();
            case CallOutcome.ProtocolError<CheckoutView> e -> e.code() + " " + e.content();
        };
    }
}
