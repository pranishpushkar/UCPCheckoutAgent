package dev.ucplab.runtime;

import dev.ucplab.core.agent.ToolSpec;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exactly the five tools of 6.2, in order (I6). Built for one run, because {@code ask_user} is bound to that run's
 * {@link UserChannel}.
 */
public final class ToolRegistry {

    public static final List<String> NAMES = List.of(SearchCatalogTool.NAME, GetProductTool.NAME,
            ProposePurchaseTool.NAME, GetPurchaseStatusTool.NAME, AskUserTool.NAME);

    private final List<Tool> tools;

    private ToolRegistry(List<Tool> tools) {
        this.tools = List.copyOf(tools);
        if (!this.tools.stream().map(t -> t.spec().name()).toList().equals(NAMES)) {
            throw new IllegalStateException("the tools must be exactly " + NAMES);
        }
    }

    /** The five tools over {@code ports}, with {@code ask_user} on {@code user} and its timeout. */
    public static ToolRegistry of(ToolPorts ports, UserChannel user, Duration askTimeout) {
        Objects.requireNonNull(ports, "ports");
        return new ToolRegistry(List.of(new SearchCatalogTool(ports), new GetProductTool(ports),
                new ProposePurchaseTool(ports), new GetPurchaseStatusTool(ports), new AskUserTool(user, askTimeout)));
    }

    public List<ToolSpec> specs() {
        return tools.stream().map(Tool::spec).toList();
    }

    /** The tool with this name; empty for any other name the model sends. */
    public Optional<Tool> find(String name) {
        Objects.requireNonNull(name, "name");
        return tools.stream().filter(t -> t.spec().name().equals(name)).findFirst();
    }
}
