package dev.ucplab.ucp.mcp;

import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The JSON-RPC 2.0 {@code tools/call} body of one MCP call (8.4). The agent names itself in
 * {@code meta["ucp-agent"].profile}; a mutating tool carries its key in {@code meta["idempotency-key"]}, a read carries
 * none. The JSON-RPC {@code id} is drawn once from the {@link IdGenerator}, and the body is canonical JSON frozen for the
 * call, so every retry sends the same bytes (8.4, I7); the {@code Request-Id} of each attempt is the executor's.
 */
public final class McpEnvelope {

    /** The tools 8.4 names whose call changes state at the store: each needs an idempotency key. */
    public static final Set<String> MUTATING_TOOLS = Set.of("create_checkout", "update_checkout", "cancel_checkout");
    /** The tools 8.4 names that only read: none carries a key. */
    public static final Set<String> READ_TOOLS = Set.of("get_checkout", "search_catalog");

    private McpEnvelope() {
    }

    /**
     * @param arguments the tool's own arguments, without {@code meta} (this class adds it)
     * @throws IllegalArgumentException for a tool 8.4 does not name, a mutating tool without a key, a read with one, or
     *     arguments that already hold {@code meta}
     */
    public static FrozenBody toolCall(
            IdGenerator ids, String tool, Map<String, Object> arguments, URI agentProfile, Optional<IdempotencyKey> key) {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(agentProfile, "agentProfile");
        Objects.requireNonNull(key, "key");
        if (MUTATING_TOOLS.contains(tool)) {
            if (key.isEmpty()) {
                throw new IllegalArgumentException("a mutating tool needs an idempotency key (8.4): " + tool);
            }
        } else if (READ_TOOLS.contains(tool)) {
            if (key.isPresent()) {
                throw new IllegalArgumentException("a read carries no idempotency key (8.4): " + tool);
            }
        } else {
            throw new IllegalArgumentException("not an MCP tool of 8.4: " + tool);
        }
        if (arguments.containsKey("meta")) {
            throw new IllegalArgumentException("arguments must not hold meta; the envelope adds it");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ucp-agent", Map.of("profile", agentProfile.toString()));
        key.ifPresent(k -> meta.put("idempotency-key", k.value()));
        Map<String, Object> args = new LinkedHashMap<>(arguments);
        args.put("meta", meta);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", ids.next());
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", tool, "arguments", args));
        return new FrozenBody(CanonicalJson.write(envelope));
    }
}
