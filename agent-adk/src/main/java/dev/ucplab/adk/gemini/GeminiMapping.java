package dev.ucplab.adk.gemini;

import com.google.genai.JsonSerializable;
import com.google.genai.types.AutomaticFunctionCallingConfig;
import com.google.genai.types.BlockedReason;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import dev.ucplab.adk.gemini.GeminiException.Reason;
import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.json.LenientJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Maps 6.5's ports to a genai request and the response back (C15, ADR 0004 items 2 and 3). Pure: no network, no
 * logging, no state beyond what the caller passes in.
 *
 * <p><b>Thought signatures.</b> Gemini 3 and 4 models attach a {@code thoughtSignature} to the first function call part
 * of a step and refuse the next request with a 400 if it does not come back on that part ("The first functionCall part
 * in each step of the current turn must include its thought_signature",
 * https://aistudio.google.com/docs/thought-signatures?generate-content=true, read 2026-11-13). 6.5's
 * {@code Message.Model} carries no signature, so the client remembers each function call part exactly as received,
 * by call id, and {@link #contents} sends it back unchanged.
 *
 * <p><b>Usage.</b> Input is {@code promptTokenCount} plus {@code toolUsePromptTokenCount}; output is
 * {@code candidatesTokenCount} plus {@code thoughtsTokenCount}. The genai sources (1.80.0) report thinking separately
 * from the candidates ("totalTokenCount ... is the sum of prompt_token_count, candidates_token_count,
 * tool_use_prompt_token_count, and thoughts_token_count"), and the pricing page bills them at the output rate
 * ("Output price (including thinking tokens)", https://aistudio.google.com/docs/pricing, read 2026-11-13), so they count
 * toward the TOKENS budget, the report's tokens and its cost. A response without usage metadata, or without a prompt
 * count, fails the call ({@link Reason#MISSING_USAGE}) rather than running unmetered.
 */
final class GeminiMapping {

    /** Finish reasons that mean the answer was blocked. */
    private static final Set<FinishReason.Known> BLOCKED = Set.of(FinishReason.Known.SAFETY,
            FinishReason.Known.RECITATION, FinishReason.Known.BLOCKLIST, FinishReason.Known.PROHIBITED_CONTENT,
            FinishReason.Known.SPII, FinishReason.Known.IMAGE_SAFETY, FinishReason.Known.IMAGE_PROHIBITED_CONTENT,
            FinishReason.Known.IMAGE_RECITATION);

    /** Finish reasons that mean the function call itself is unusable. */
    private static final Set<FinishReason.Known> MALFORMED = Set.of(FinishReason.Known.MALFORMED_FUNCTION_CALL,
            FinishReason.Known.UNEXPECTED_TOOL_CALL, FinishReason.Known.TOO_MANY_TOOL_CALLS);

    private GeminiMapping() {
    }

    /** The request's config: the system prompt, the tools as declarations with their JSON schemas, no AFC. */
    static GenerateContentConfig config(String systemPrompt, List<ToolSpec> tools, int maxOutputTokens) {
        GenerateContentConfig.Builder config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
                // Automatic function calling applies only to Java Method tools, which we never pass; say so anyway.
                .automaticFunctionCalling(AutomaticFunctionCallingConfig.builder().disable(true).build())
                .candidateCount(1)
                .maxOutputTokens(maxOutputTokens);
        if (!tools.isEmpty()) {
            List<FunctionDeclaration> declarations = new ArrayList<>();
            for (ToolSpec spec : tools) {
                // Passed through as a map: the SDK copies functionDeclarations into the request unchanged.
                declarations.add(FunctionDeclaration.builder()
                        .name(spec.name())
                        .description(spec.description())
                        .parametersJsonSchema(LenientJson.readObject(spec.parametersJsonSchema()))
                        .build());
            }
            config.tools(List.of(Tool.builder().functionDeclarations(declarations).build()));
        }
        return config.build();
    }

    /** The function names in a built config, in order: what the model is actually offered (I6). */
    static List<String> declaredNames(GenerateContentConfig config) {
        List<String> names = new ArrayList<>();
        for (Tool tool : config.tools().orElse(List.of())) {
            for (FunctionDeclaration d : tool.functionDeclarations().orElse(List.of())) {
                names.add(d.name().orElse(""));
            }
        }
        return List.copyOf(names);
    }

    /**
     * The conversation's messages as contents, oldest first. Consecutive tool results go in one user content, as the
     * answers to one step's calls.
     *
     * @param received the function call parts this client received, by our call id, to send back unchanged
     */
    static List<Content> contents(Conversation conversation, Function<String, Optional<Part>> received) {
        List<Content> contents = new ArrayList<>();
        List<Part> results = new ArrayList<>();
        for (Message m : conversation.messages()) {
            if (m instanceof Message.ToolResult r) {
                results.add(functionResponse(r, received.apply(r.callId())));
                continue;
            }
            flush(results, contents);
            switch (m) {
                case Message.User u -> contents.add(Content.builder().role("user")
                        .parts(List.of(Part.fromText(u.text()))).build());
                case Message.Model model -> {
                    List<Part> parts = new ArrayList<>();
                    if (!model.text().isEmpty()) {
                        parts.add(Part.fromText(model.text()));
                    }
                    for (ToolCall call : model.toolCalls()) {
                        parts.add(received.apply(call.id())
                                .orElseGet(() -> Part.fromFunctionCall(call.name(), call.arguments())));
                    }
                    contents.add(Content.builder().role("model").parts(parts).build());
                }
                case Message.ToolResult r -> throw new IllegalStateException("handled above");
            }
        }
        flush(results, contents);
        return List.copyOf(contents);
    }

    private static void flush(List<Part> results, List<Content> contents) {
        if (!results.isEmpty()) {
            contents.add(Content.builder().role("user").parts(List.copyOf(results)).build());
            results.clear();
        }
    }

    private static Part functionResponse(Message.ToolResult r, Optional<Part> call) {
        // Every tool answers with a JSON object; anything else goes back as a string under "result".
        Map<String, Object> response;
        try {
            response = LenientJson.readObject(r.json());
        } catch (IllegalArgumentException e) {
            response = Map.of("result", r.json());
        }
        FunctionResponse.Builder fr = FunctionResponse.builder().name(r.tool()).response(response);
        // The model's own call id, when it sent one, pairs the answer with the call; ours stay on our side.
        call.flatMap(Part::functionCall).flatMap(FunctionCall::id).ifPresent(fr::id);
        return Part.builder().functionResponse(fr.build()).build();
    }

    /**
     * The response as a turn: the text of the non-thought parts, the function calls in order, and the usage.
     *
     * @param newId a fresh call id, used when the model sends none (ADR 0004 item 2)
     * @param remember given each function call part as received, under the call id the turn uses
     * @throws GeminiException with a fixed reason for a blocked, empty or malformed answer, or missing usage
     */
    static ModelTurn turn(GenerateContentResponse response, Supplier<String> newId, Remember remember) {
        response.promptFeedback().flatMap(f -> f.blockReason()).ifPresent(b -> {
            if (b.knownEnum() != BlockedReason.Known.BLOCKED_REASON_UNSPECIFIED) {
                throw new GeminiException(Reason.BLOCKED);
            }
        });
        List<Candidate> candidates = response.candidates().orElse(List.of());
        if (candidates.isEmpty()) {
            throw new GeminiException(Reason.EMPTY_RESPONSE);
        }
        Candidate candidate = candidates.getFirst();
        Optional<FinishReason.Known> finish = candidate.finishReason().map(FinishReason::knownEnum);
        if (finish.isPresent() && BLOCKED.contains(finish.get())) {
            throw new GeminiException(Reason.BLOCKED);
        }
        if (finish.isPresent() && MALFORMED.contains(finish.get())) {
            throw new GeminiException(Reason.MALFORMED_RESPONSE);
        }
        List<Part> parts = candidate.content().flatMap(Content::parts).orElse(List.of());
        if (parts.isEmpty()) {
            throw new GeminiException(Reason.EMPTY_RESPONSE);
        }
        Usage usage = usage(response.usageMetadata());
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        Map<String, Part> seen = new LinkedHashMap<>();
        for (Part part : parts) {
            if (part.functionCall().isPresent()) {
                FunctionCall fc = part.functionCall().get();
                String name = fc.name().filter(n -> !n.isBlank())
                        .orElseThrow(() -> new GeminiException(Reason.MALFORMED_RESPONSE));
                String id = fc.id().filter(i -> !i.isBlank() && !seen.containsKey(i)).orElseGet(newId);
                calls.add(new ToolCall(id, name, arguments(fc.args().orElse(Map.of()))));
                seen.put(id, part);
            } else if (part.text().isPresent() && !part.thought().orElse(false)) {
                text.append(part.text().get());
            }
        }
        if (calls.isEmpty() && text.isEmpty()) {
            throw new GeminiException(Reason.EMPTY_RESPONSE);
        }
        seen.forEach(remember::part);
        return new ModelTurn(text.toString(), calls, usage);
    }

    /** Where {@link #turn} hands each function call part as received, under its call id. */
    @FunctionalInterface
    interface Remember {
        void part(String callId, Part part);
    }

    private static Usage usage(Optional<GenerateContentResponseUsageMetadata> metadata) {
        GenerateContentResponseUsageMetadata u = metadata.orElseThrow(() -> new GeminiException(Reason.MISSING_USAGE));
        long prompt = u.promptTokenCount().orElseThrow(() -> new GeminiException(Reason.MISSING_USAGE));
        long input = prompt + u.toolUsePromptTokenCount().orElse(0);
        long output = (long) u.candidatesTokenCount().orElse(0) + u.thoughtsTokenCount().orElse(0);
        if (input < 0 || output < 0) {
            throw new GeminiException(Reason.MALFORMED_RESPONSE);
        }
        return new Usage(input, output);
    }

    /**
     * The model's arguments in core's JSON value set (Long, BigDecimal, String, Boolean, List, Map, null), the same as a
     * script's, by writing them with the SDK's mapper and reading them back with {@link LenientJson}.
     */
    private static Map<String, Object> arguments(Map<String, Object> args) {
        try {
            return LenientJson.readObject(JsonSerializable.toJsonString(args));
        } catch (RuntimeException e) {
            throw new GeminiException(Reason.MALFORMED_RESPONSE);
        }
    }
}
