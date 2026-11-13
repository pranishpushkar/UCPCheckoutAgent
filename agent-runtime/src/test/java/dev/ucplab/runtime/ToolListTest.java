package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.json.CanonicalJson;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolListTest {

    private static final ToolRegistry REGISTRY = ToolRegistry.of(
            Stubs.ports(Stubs.catalog(List.of(), id -> Stubs.notFound()), new Stubs.RecordingOrchestrator()),
            (q, t) -> Optional.empty(), Duration.ofMinutes(5));

    // Covers: 6.2, I6 (exactly the five tools, in order)
    @Test
    void registry_hasExactlyTheFiveToolsInOrder() {
        assertThat(REGISTRY.specs()).extracting(ToolSpec::name).containsExactly(
                "search_catalog", "get_product", "propose_purchase", "get_purchase_status", "ask_user");
        assertThat(ToolRegistry.NAMES).isEqualTo(REGISTRY.specs().stream().map(ToolSpec::name).toList());
    }

    // Covers: 6.2, I6 (no other name reaches a tool: approve, complete and cancel do not exist)
    @Test
    void registry_unknownName_findsNothing() {
        for (String name : List.of("approve", "complete_purchase", "cancel_purchase", "decide", "Search_Catalog", "")) {
            assertThat(REGISTRY.find(name)).as(name).isEmpty();
        }
        assertThat(REGISTRY.find("ask_user")).isPresent();
    }

    // Covers: 6.2 (each schema is a closed object with the 6.2 inputs, all required)
    @Test
    void specs_haveTheSixPointTwoSchemas() {
        Map<String, List<String>> inputs = Map.of(
                "search_catalog", List.of("query", "max_results"),
                "get_product", List.of("item_id"),
                "propose_purchase", List.of("merchant_id", "items", "rationale"),
                "get_purchase_status", List.of("intent_id"),
                "ask_user", List.of("question"));
        for (ToolSpec spec : REGISTRY.specs()) {
            Map<String, Object> schema = CanonicalJson.readObject(spec.parametersJsonSchema());
            assertThat(schema).as(spec.name()).containsEntry("type", "object")
                    .containsEntry("additionalProperties", false);
            assertThat(List.copyOf(((Map<?, ?>) schema.get("properties")).keySet())).as(spec.name())
                    .map(String::valueOf).containsExactlyInAnyOrderElementsOf(inputs.get(spec.name()));
            assertThat(List.copyOf((List<?>) schema.get("required"))).as(spec.name())
                    .map(String::valueOf).containsExactlyInAnyOrderElementsOf(inputs.get(spec.name()));
            assertThat(spec.description()).as(spec.name()).isNotBlank();
        }
    }

    // Covers: 6.2 (the numeric limits are in the schemas: max_results 1..10, quantity 1..5, at most 5 items,
    // rationale 300, question 500)
    @Test
    void specs_carryTheLimits() {
        String search = spec("search_catalog");
        String propose = spec("propose_purchase");
        assertThat(search).contains("\"max_results\":{\"description\":\"The most items to return.\",\"maximum\":10,"
                + "\"minimum\":1");
        assertThat(propose).contains("\"maxItems\":5").contains("\"quantity\":{\"maximum\":5,\"minimum\":1")
                .contains("\"maxLength\":300");
        assertThat(spec("ask_user")).contains("\"maxLength\":500");
    }

    private static String spec(String name) {
        return REGISTRY.find(name).orElseThrow().spec().parametersJsonSchema();
    }
}
