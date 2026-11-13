package dev.ucplab.core.agent;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * How a live model client is built (C15, ADR 0004). Fixed at construction, because 6.5's {@code generate} has no
 * timeout parameter ([CONTRACT]).
 *
 * @param model the model id; empty for the provider's pinned model
 * @param baseUrl the API's base URL; empty for the provider's real endpoint (tests point it at a loopback stub)
 * @param callTimeout the timeout of one model call, at most {@link #MAX_CALL_TIMEOUT}, the run's whole wall-clock
 *     budget (6.3)
 * @param requestedTools given, for every call, the function names in the request the client actually built (I6). The
 *     live I6 check reads this, not the tool list the client was handed
 * @param answeringModel given, for every call whose response names one, the model version that answered it, as an
 *     id. The id sent can be routed to another model (a retired id is), so the live report lists what answered. It is
 *     an observer, not a {@link ModelTurn} field, because the turn's shape is 6.5's [CONTRACT]
 */
public record ModelClientSettings(Optional<String> model, Optional<URI> baseUrl, Duration callTimeout,
                                  Consumer<List<String>> requestedTools, Consumer<String> answeringModel) {

    /** 6.3's default wall-clock budget: one call may never be allowed more than the whole run. */
    public static final Duration MAX_CALL_TIMEOUT = Duration.ofSeconds(30);

    public ModelClientSettings {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(callTimeout, "callTimeout");
        Objects.requireNonNull(requestedTools, "requestedTools");
        Objects.requireNonNull(answeringModel, "answeringModel");
        if (model.isPresent() && model.get().isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (callTimeout.isNegative() || callTimeout.isZero() || callTimeout.compareTo(MAX_CALL_TIMEOUT) > 0) {
            throw new IllegalArgumentException("callTimeout must be positive and at most " + MAX_CALL_TIMEOUT);
        }
    }

    /** The provider's pinned model and endpoint, a call timeout of {@link #MAX_CALL_TIMEOUT}, and no observers. */
    public static ModelClientSettings defaults() {
        return new ModelClientSettings(Optional.empty(), Optional.empty(), MAX_CALL_TIMEOUT, names -> { },
                version -> { });
    }

    public ModelClientSettings withModel(String model) {
        return new ModelClientSettings(Optional.of(model), baseUrl, callTimeout, requestedTools, answeringModel);
    }

    public ModelClientSettings withBaseUrl(URI baseUrl) {
        return new ModelClientSettings(model, Optional.of(baseUrl), callTimeout, requestedTools, answeringModel);
    }

    public ModelClientSettings withCallTimeout(Duration callTimeout) {
        return new ModelClientSettings(model, baseUrl, callTimeout, requestedTools, answeringModel);
    }

    public ModelClientSettings withRequestedTools(Consumer<List<String>> requestedTools) {
        return new ModelClientSettings(model, baseUrl, callTimeout, requestedTools, answeringModel);
    }

    public ModelClientSettings withAnsweringModel(Consumer<String> answeringModel) {
        return new ModelClientSettings(model, baseUrl, callTimeout, requestedTools, answeringModel);
    }
}
