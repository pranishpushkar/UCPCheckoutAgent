package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.IntentId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IdempotencyKeyTest {

    private static final IntentId INTENT_1 = new IntentId("intent-1");

    // Covers: 7.4, D3. Golden values computed independently with Python hashlib and uuid: the first 16 bytes of
    // sha256(intentId|op|revision), version nibble 8, variant bits 10.
    @ParameterizedTest
    @CsvSource({
            "intent-1,CREATE,0,e35d7b1d-1af7-8e2f-b8ee-03fd389fdb84",
            "intent-1,UPDATE,0,470a2aab-e0a1-87d7-84cf-3923ac626ef7",
            "intent-1,UPDATE,1,d87e9f2e-0184-880f-8135-0a765659f54d",
            "intent-1,COMPLETE,0,77473a1e-a94d-8a6c-ac76-5f4275bb9b90",
            "intent-1,COMPLETE,1,bdcdb44d-f238-814c-87dc-d261f63db769",
            "intent-1,CANCEL,0,3bc84c30-8935-8708-8c1e-a160efa059a5",
            "intent-2,CREATE,0,e43ea44e-248c-848c-89b9-5f013b82ba66"
    })
    void idempotencyKey_matchesFormula_forGoldenVectors(String intent, MutatingOp op, int revision, String expected) {
        assertThat(IdempotencyKey.derive(new IntentId(intent), op, revision).value()).isEqualTo(expected);
    }

    // Covers: 7.4 (format: a canonical lower-case UUID of version 8 and variant 10, as the OpenAPI's format: uuid asks)
    @Test
    void idempotencyKey_isAUuidVersion8_always() {
        String key = IdempotencyKey.derive(INTENT_1, MutatingOp.UPDATE, 7).value();

        assertThat(key).matches("[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(java.util.UUID.fromString(key).version()).isEqualTo(8);
        assertThat(java.util.UUID.fromString(key).variant()).isEqualTo(2);
    }

    // Covers: I7, D3 (a retry recomputes the same key)
    @Test
    void idempotencyKey_isDeterministic_forSameInputs() {
        assertThat(IdempotencyKey.derive(INTENT_1, MutatingOp.COMPLETE, 0))
                .isEqualTo(IdempotencyKey.derive(new IntentId("intent-1"), MutatingOp.COMPLETE, 0));
    }

    // Covers: 7.4, I7 (each approval gets its own complete key), R7 (keys never shared across operations)
    @Test
    void idempotencyKey_differs_acrossOpsRevisionsAndIntents() {
        Set<IdempotencyKey> keys = new HashSet<>();
        for (MutatingOp op : MutatingOp.values()) {
            keys.add(IdempotencyKey.derive(INTENT_1, op, 0));
            keys.add(IdempotencyKey.derive(new IntentId("intent-2"), op, 0));
        }
        keys.add(IdempotencyKey.derive(INTENT_1, MutatingOp.UPDATE, 1));
        keys.add(IdempotencyKey.derive(INTENT_1, MutatingOp.COMPLETE, 1));
        keys.add(IdempotencyKey.derive(INTENT_1, MutatingOp.COMPLETE, 2));

        assertThat(keys).hasSize(11);
    }

    // Covers: 7.4 (revision is 0 for create and cancel)
    @ParameterizedTest
    @CsvSource({"CREATE,1", "CANCEL,1", "CREATE,-1", "UPDATE,-1", "COMPLETE,-1"})
    void idempotencyKey_throws_whenRevisionInvalidForOp(MutatingOp op, int revision) {
        assertThatThrownBy(() -> IdempotencyKey.derive(INTENT_1, op, revision))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 7.4 (op is hashed by its lowercase wire name)
    @Test
    void mutatingOp_wireNames_areLowercase() {
        assertThat(MutatingOp.values())
                .extracting(MutatingOp::wireName)
                .containsExactly("create", "update", "complete", "cancel");
    }
}
