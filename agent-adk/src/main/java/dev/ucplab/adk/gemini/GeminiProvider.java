package dev.ucplab.adk.gemini;

import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelClientProvider;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelKey;

/** Registers {@link GeminiModelClient} as {@code "gemini"} for {@code ModelClients.load} (m5-plan section 3). */
public final class GeminiProvider implements ModelClientProvider {

    public static final String NAME = "gemini";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ModelClient create(ModelKey key, ModelClientSettings settings) {
        return new GeminiModelClient(key, settings);
    }
}
