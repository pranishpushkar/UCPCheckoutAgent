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

    // Covers: 7.4, D3. Golden values computed independently with Python hashlib and base64.b32encode.
    @ParameterizedTest
    @CsvSource({
            "intent-1,CREATE,0,ucp-4NOXWHI267PC6OHOAP6TRH63QQ",
            "intent-1,UPDATE,0,ucp-I4FCVK7AUH35ORGPHER2YYTO66",
            "intent-1,UPDATE,1,ucp-3B7J6LQBQRMA7QJVBJ3FMWPVJW",
            "intent-1,COMPLETE,0,ucp-O5DTUHVJJXVGYLDWL5BHLO43SB",
            "intent-1,COMPLETE,1,ucp-XXG3ITPSHBQUYR642JQ7MPNXNG",
            "intent-1,CANCEL,0,ucp-HPEEYMEJGVDQQDA6UFQO7ICZUW",
            "intent-2,CREATE,0,ucp-4Q7KITRERTKIYSNZL4ATXAV2M2"
    })
    void idempotencyKey_matchesFormula_forGoldenVectors(String intent, MutatingOp op, int revision, String expected) {
        assertThat(IdempotencyKey.derive(new IntentId(intent), op, revision).value()).isEqualTo(expected);
    }

    // Covers: 7.4 (format: 'ucp-' plus 26 base32 characters)
    @Test
    void idempotencyKey_hasPrefixAnd26Base32Chars_always() {
        String key = IdempotencyKey.derive(INTENT_1, MutatingOp.UPDATE, 7).value();

        assertThat(key).matches("ucp-[A-Z2-7]{26}");
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
