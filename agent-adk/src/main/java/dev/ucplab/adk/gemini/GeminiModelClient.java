package dev.ucplab.adk.gemini;

import com.google.genai.Client;
import com.google.genai.JsonSerializable;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import dev.ucplab.adk.gemini.GeminiException.Reason;
import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolSpec;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The live {@link ModelClient} (6.5, ADR 0004): one {@code client.models.generateContent} call per turn on
 * {@code com.google.genai:google-genai}. It never runs a tool; the agent loop does (6.3, I6).
 *
 * <p>The SDK client is configured explicitly, so no environment variable changes it silently (ADR 0004 item 4):
 * {@code vertexAI(false)}, an explicit base URL, one attempt (no retry spends the budget out of sight), and the
 * per-call timeout from {@link ModelClientSettings#callTimeout}, at most the run's 30 s wall-clock budget. It refuses
 * to start if {@code GOOGLE_GENAI_CLIENT_MODE} is set, since the SDK's replay modes read it and cannot be turned off
 * from outside its package. The key goes only into the SDK's builder, which sends it in the {@code x-goog-api-key}
 * header.
 *
 * <p>Every exception that leaves {@link #generate} is a {@link GeminiException} with a fixed reason and a cause from
 * 6.5's closed vocabulary: the HTTP status, the answer's finish reason, or an exception's class, never its content (I8).
 * The SDK keeps no response header, so 6.3's wait is the {@code retryDelay} of the error's {@code google.rpc.RetryInfo}
 * detail, which the SDK writes into its error's message; only that number is read from it, and the message is neither
 * kept nor logged. For the live
 * I6 check, {@link ModelClientSettings#requestedTools} is given the function names of every request this client
 * actually built, and {@link ModelClientSettings#answeringModel} the model version each response names.
 *
 * <p>It keeps the function call parts it received, by call id, for the thought signatures (see
 * {@link GeminiMapping}), so one client serves one run's conversation. It holds no lock: the map is concurrent and a
 * call is never made while holding anything.
 */
public final class GeminiModelClient implements ModelClient, AutoCloseable {

    static final String CLIENT_MODE = "GOOGLE_GENAI_CLIENT_MODE";
    private static final String ID_PREFIX = "ucplab-call-";
    /**
     * 6.3: a RetryInfo detail as the SDK writes it after {@code "Details: "}, one compact JSON detail a line, with its
     * {@code retryDelay} in the JSON form of a {@code google.protobuf.Duration}: seconds with up to nine decimals.
     */
    private static final Pattern RETRY_INFO = Pattern.compile("(?:Details: )?\\{\"@type\":\"type\\.googleapis\\.com/"
            + "google\\.rpc\\.RetryInfo\",\"retryDelay\":\"([0-9]{1,6})(?:\\.([0-9]{1,9}))?s\"}");

    private final Client client;
    private final String model;
    private final ModelClientSettings settings;
    private final Map<String, Part> received = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();

    public GeminiModelClient(ModelKey key, ModelClientSettings settings) {
        this(key, settings, System::getenv);
    }

    /** @param env the environment, for the {@code GOOGLE_GENAI_CLIENT_MODE} check (tests pass their own) */
    GeminiModelClient(ModelKey key, ModelClientSettings settings, UnaryOperator<String> env) {
        Objects.requireNonNull(key, "key");
        this.settings = Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(env, "env");
        if (env.apply(CLIENT_MODE) != null || !key.visibleAscii()) {
            throw new GeminiException(Reason.CONFIGURATION);
        }
        this.model = settings.model().orElse(GeminiModels.MODEL);
        try {
            // 6.5: the request config once through the SDK's own serialiser, so a setting it refuses fails here, when
            // the service starts, and not on the first message.
            JsonSerializable.toJsonNode(GeminiMapping.config("", List.of(), GeminiModels.MAX_OUTPUT_TOKENS));
            this.client = Client.builder()
                    .apiKey(key.reveal())
                    .vertexAI(false)
                    .httpOptions(HttpOptions.builder()
                            .baseUrl(settings.baseUrl().map(Object::toString).orElse(GeminiModels.BASE_URL))
                            .timeout(Math.toIntExact(settings.callTimeout().toMillis()))
                            .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                            .build())
                    .build();
        } catch (RuntimeException e) {
            throw new GeminiException(Reason.CONFIGURATION);
        }
    }

    @Override
    public ModelTurn generate(Conversation conversation, List<ToolSpec> tools) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(tools, "tools");
        try {
            GenerateContentConfig config = GeminiMapping.config(conversation.systemPrompt(), tools,
                    GeminiModels.MAX_OUTPUT_TOKENS);
            List<Content> contents = GeminiMapping.contents(conversation,
                    id -> Optional.ofNullable(received.get(id)));
            settings.requestedTools().accept(GeminiMapping.declaredNames(config));
            GenerateContentResponse response = client.models.generateContent(model, contents, config);
            GeminiMapping.answeringModel(response).ifPresent(settings.answeringModel());
            return GeminiMapping.turn(response, () -> ID_PREFIX + nextId.incrementAndGet(), received::put);
        } catch (GeminiException e) {
            throw e;
        } catch (ApiException e) {
            throw new GeminiException(reason(e.code()), "http_" + Math.max(0, e.code()), retryDelay(e.message()));
        } catch (GenAiIOException e) {
            throw timedOut(e) ? new GeminiException(Reason.TIMEOUT, "timeout")
                    : new GeminiException(Reason.NETWORK, named("network_", e.getCause() == null ? e : e.getCause()));
        } catch (RuntimeException e) {
            throw new GeminiException(Reason.INTERNAL, named("exception_", e));
        }
    }

    /**
     * 6.3: the {@code retryDelay} of the first RetryInfo among the details of the SDK's error message, when there is one
     * in the exact form above; a RetryInfo text in the message itself, before the details, is not read.
     */
    static Optional<Duration> retryDelay(String message) {
        if (message == null) {
            return Optional.empty();
        }
        int details = message.startsWith("Details: ") ? 0 : message.indexOf("\nDetails: ");
        if (details < 0) {
            return Optional.empty();
        }
        for (String line : message.substring(details).strip().split("\n")) {
            Matcher m = RETRY_INFO.matcher(line);
            if (m.matches()) {
                long nanos = m.group(2) == null ? 0 : Long.parseLong((m.group(2) + "00000000").substring(0, 9));
                return Optional.of(Duration.ofSeconds(Long.parseLong(m.group(1)), nanos));
            }
        }
        return Optional.empty();
    }

    /** 6.5: {@code prefix} and the class's simple name, as identifier characters, at most 64 in all; never a message. */
    static String named(String prefix, Throwable e) {
        String name = e.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
        String cause = prefix + (name.isEmpty() ? "anonymous" : name);
        return cause.length() > 64 ? cause.substring(0, 64) : cause;
    }

    static Reason reason(int status) {
        if (status == 401 || status == 403) {
            return Reason.UNAUTHORIZED;
        }
        if (status == 429) {
            return Reason.RATE_LIMITED;
        }
        if (status >= 500) {
            return Reason.SERVER_ERROR;
        }
        return status >= 400 ? Reason.BAD_REQUEST : Reason.INTERNAL;
    }

    /** OkHttp reports its call timeout as an {@link InterruptedIOException} somewhere in the cause chain. */
    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    /** Releases the SDK client's HTTP resources. Never throws. */
    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException e) {
            // Nothing to report: closing frees idle connections only.
        }
    }

    @Override
    public String toString() {
        return "GeminiModelClient[" + model + "]";
    }
}
