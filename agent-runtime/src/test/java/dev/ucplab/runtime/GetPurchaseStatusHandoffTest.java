package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.agent.RunId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** H5 C7c: {@code get_purchase_status} at a handed-off intent (6.2 [CONTRACT], I6). */
class GetPurchaseStatusHandoffTest {

    private static final IntentId ID = new IntentId("intent-1");

    private static Map<String, Object> status(IntentState state) {
        Stubs.RecordingOrchestrator orchestrator = new Stubs.RecordingOrchestrator();
        orchestrator.statuses = Map.of(ID, new IntentStatus(ID, state, Optional.empty(), Optional.empty()));
        GetPurchaseStatusTool tool = new GetPurchaseStatusTool(Stubs.ports(Stubs.catalog(List.of(), id -> Stubs.notFound()), orchestrator));
        String json = tool.run(new ToolContext(Stubs.ALICE, new RunId("run-1")), Map.of("intent_id", ID.value())).json();
        ResultFields.check(json);
        return new HashMap<>(CanonicalJson.readObject(json));
    }

    // Covers: 6.2, I6 (HANDED_OFF: the state and the fixed note "handed to the store for payment", and nothing else:
    // never the continue_url, which IntentStatus does not even carry)
    @Test
    void handedOff_returnsTheStateAndTheFixedNote() {
        assertThat(status(IntentState.HANDED_OFF)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "state", "HANDED_OFF", "note", "handed to the store for payment"));
    }

    // Covers: 6.2 (no other state has a note)
    @Test
    void everyOtherState_hasNoNote() {
        for (IntentState state : IntentState.values()) {
            if (state != IntentState.HANDED_OFF) {
                assertThat(status(state)).as(state.name()).doesNotContainKey("note");
            }
        }
    }
}
