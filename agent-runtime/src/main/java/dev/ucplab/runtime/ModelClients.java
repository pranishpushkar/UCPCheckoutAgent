package dev.ucplab.runtime;

import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelClientProvider;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelKey;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Finds a live {@link ModelClient} by name through {@link ServiceLoader} (m5-plan section 3, Decision 1):
 * {@code ModelClients.load("gemini", key)}. The provider is on the classpath only when agent-adk is (the
 * {@code eval-live} profile); without it, {@link Unavailable} says so with a fixed message.
 */
public final class ModelClients {

    private ModelClients() {
    }

    /** No provider of that name on the classpath. The message names only our own provider name. */
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unavailable(String name) {
            super("no model client provider \"" + name + "\" on the classpath", null, false, false);
        }
    }

    /** As {@link #load(String, ModelKey, ModelClientSettings)} with the provider's pinned model and endpoint. */
    public static ModelClient load(String name, ModelKey key) {
        return load(name, key, ModelClientSettings.defaults());
    }

    /** @throws Unavailable if no provider is registered under {@code name} */
    public static ModelClient load(String name, ModelKey key, ModelClientSettings settings) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(settings, "settings");
        return provider(name).create(key, settings);
    }

    /** Whether a provider named {@code name} is on the classpath, without building a client. */
    public static boolean available(String name) {
        return providers().stream().anyMatch(p -> p.name().equals(name));
    }

    private static ModelClientProvider provider(String name) {
        Objects.requireNonNull(name, "name");
        return providers().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new Unavailable(name));
    }

    private static List<ModelClientProvider> providers() {
        return ServiceLoader.load(ModelClientProvider.class).stream().map(ServiceLoader.Provider::get).toList();
    }
}
