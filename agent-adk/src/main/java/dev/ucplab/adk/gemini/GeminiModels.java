package dev.ucplab.adk.gemini;

/** The pinned model and endpoint of the live client (C15, m5-plan section 10). */
final class GeminiModels {

    /**
     * {@code gemini-3.8-flash}: listed as Stable in the Gemini 3 table of https://aistudio.google.com/docs/models (where
     * https://ai.google.dev/gemini-api/docs/models redirects), read 2026-11-13. The same model as the C1 live spike.
     */
    static final String MODEL = "gemini-3.8-flash";

    /** The Gemini Developer API, set explicitly so GOOGLE_GEMINI_BASE_URL cannot redirect it (ADR 0004 item 4). */
    static final String BASE_URL = "https://generativelanguage.googleapis.com";

    /**
     * The output cap of one call (6.3). Generous, so a turn's thinking cannot cut a function call short; the run's
     * token ceiling is the real limit. TODO(VERIFY): whether thinking tokens count toward maxOutputTokens on Gemini 3 is
     * not stated in the genai sources or the thinking guide read on 2026-11-13.
     */
    static final int MAX_OUTPUT_TOKENS = 8192;

    private GeminiModels() {
    }
}
