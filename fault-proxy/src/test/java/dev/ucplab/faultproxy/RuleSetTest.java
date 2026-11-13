package dev.ucplab.faultproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.faultproxy.Action.CorruptResponse;
import dev.ucplab.faultproxy.Action.Delay;
import dev.ucplab.faultproxy.Action.DuplicateRequest;
import dev.ucplab.faultproxy.Action.FailBeforeForward;
import dev.ucplab.faultproxy.Action.ForwardThenDelayResponse;
import dev.ucplab.faultproxy.Action.ForwardThenDropResponse;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleSetTest {

    // Covers: §12 (the rule set example in the design parses as written)
    @Test
    void parse_readsTheDesignExample() {
        RuleSet set = RuleSet.parse("""
                {
                  "seed": 42,
                  "rules": [
                    {
                      "id": "drop-complete-once",
                      "match": { "method": "POST", "pathPattern": "/ucp/v1/checkout-sessions/*/complete", "nth": 1 },
                      "action": { "type": "forward_then_drop_response" },
                      "times": 1
                    }
                  ]
                }""");

        assertThat(set).isEqualTo(new RuleSet(42, List.of(new Rule("drop-complete-once",
                Match.of("POST", "/ucp/v1/checkout-sessions/*/complete").withNth(1),
                new ForwardThenDropResponse(), OptionalInt.of(1)))));
    }

    // Covers: §12 Action parameters (every action and its parameters)
    @Test
    void parse_readsEveryActionWithItsParameters() {
        RuleSet set = RuleSet.parse("""
                {"seed": -7, "rules": [
                  {"id": "a", "match": {}, "action": {"type": "delay", "ms": 1500}},
                  {"id": "b", "match": {"method": "GET"}, "action": {"type": "fail_before_forward", "status": 503, "retryAfterSeconds": 2}},
                  {"id": "c", "match": {"pathPattern": "/ucp/v1/**"}, "action": {"type": "fail_before_forward", "close": true}},
                  {"id": "d", "match": {"probability": 0.25}, "action": {"type": "forward_then_delay_response", "ms": 0}},
                  {"id": "e", "match": {"probability": 1}, "action": {"type": "duplicate_request"}},
                  {"id": "f", "match": {}, "action": {"type": "duplicate_request", "mode": "sequential"}},
                  {"id": "g", "match": {}, "action": {"type": "corrupt_response", "kind": "truncate"}},
                  {"id": "h", "match": {}, "action": {"type": "corrupt_response", "kind": "garble"}, "times": 3}
                ]}""");

        assertThat(set.seed()).isEqualTo(-7);
        assertThat(set.rules()).extracting(Rule::action).containsExactly(
                new Delay(1500),
                new FailBeforeForward(OptionalInt.of(503), false, OptionalInt.of(2)),
                FailBeforeForward.closeConnection(),
                new ForwardThenDelayResponse(0),
                new DuplicateRequest(DuplicateRequest.Mode.PARALLEL),
                new DuplicateRequest(DuplicateRequest.Mode.SEQUENTIAL),
                new CorruptResponse(CorruptResponse.Kind.TRUNCATE),
                new CorruptResponse(CorruptResponse.Kind.GARBLE));
        assertThat(set.rules().get(0).match()).isEqualTo(Match.any());
        assertThat(set.rules().get(1).match().method()).contains("GET");
        assertThat(set.rules().get(2).match().pathPattern()).contains(new PathPattern("/ucp/v1/**"));
        assertThat(set.rules().get(3).match().probability()).isEqualTo(OptionalDouble.of(0.25));
        assertThat(set.rules().get(4).match().probability()).isEqualTo(OptionalDouble.of(1.0));
        assertThat(set.rules().get(7).times()).isEqualTo(OptionalInt.of(3));
        assertThat(set.rules().get(0).times()).isEmpty();
        assertThat(set.rules()).extracting(r -> r.action().type()).containsExactly("delay", "fail_before_forward",
                "fail_before_forward", "forward_then_delay_response", "duplicate_request", "duplicate_request",
                "corrupt_response", "corrupt_response");
    }

    // Covers: §12 (a rule set that is not exactly valid is refused as a whole, never partly installed), D1/Decision 2
    // (the admin API never accepts a key: an extra field such as hmacKey is refused like any unknown field)
    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{\"rules\": []}",
            "{\"seed\": 1}",
            "{\"seed\": 1.5, \"rules\": []}",
            "{\"seed\": 1, \"rules\": [], \"hmacKey\": \"00\"}",
            "{\"seed\": 1, \"rules\": {}}",
            "{\"seed\": 1, \"rules\": [1]}",
            "{\"seed\": 1, \"rules\": [{\"match\": {}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \" \", \"match\": {}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"delay\", \"ms\": 1}, \"extra\": 1}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {\"host\": \"x\"}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {\"method\": \"post\"}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {\"pathPattern\": \"ucp\"}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {\"nth\": 0}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {\"nth\": 1.0}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {\"probability\": 1.01}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {\"probability\": -0.1}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {\"probability\": \"0.5\"}, \"action\": {\"type\": \"delay\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"delay\", \"ms\": 1}, \"times\": 0}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"delay\"}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"delay\", \"ms\": -1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"delay\", \"ms\": 1, \"status\": 503}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"teleport\"}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"fail_before_forward\"}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"fail_before_forward\", \"status\": 503, \"close\": true}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"fail_before_forward\", \"close\": false}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"fail_before_forward\", \"close\": true, \"retryAfterSeconds\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"fail_before_forward\", \"status\": 99}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"fail_before_forward\", \"status\": 600}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"fail_before_forward\", \"status\": 503, \"retryAfterSeconds\": -1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"forward_then_drop_response\", \"ms\": 1}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"forward_then_delay_response\"}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"duplicate_request\", \"mode\": \"twice\"}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"corrupt_response\"}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"corrupt_response\", \"kind\": \"flip\"}}]}",
            "{\"seed\": 1, \"rules\": [{\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"delay\", \"ms\": 1}},"
                    + " {\"id\": \"a\", \"match\": {}, \"action\": {\"type\": \"delay\", \"ms\": 2}}]}",
    })
    void parse_refusesAnInvalidRuleSet(String json) {
        assertThatThrownBy(() -> RuleSet.parse(json)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: §12 (an empty rule set is valid: it forwards everything)
    @Test
    void parse_acceptsAnEmptyRuleList() {
        assertThat(RuleSet.parse("{\"seed\": 0, \"rules\": []}")).isEqualTo(RuleSet.empty());
    }

    // Covers: §12 Action parameters (exactly one of status or close)
    @Test
    void failBeforeForward_needsExactlyOneOfStatusOrClose() {
        assertThatThrownBy(() -> new FailBeforeForward(OptionalInt.empty(), false, OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailBeforeForward(OptionalInt.of(503), true, OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FailBeforeForward.status(503).status()).hasValue(503);
    }

    // Covers: §12 (an absent method or path pattern matches anything)
    @Test
    void match_absentMethodAndPatternFitEverything() {
        Match any = new Match(Optional.empty(), Optional.empty(), OptionalInt.empty(), OptionalDouble.empty());

        assertThat(any.fits("DELETE", "/whatever")).isTrue();
        assertThat(Match.of("POST", "/a").fits("GET", "/a")).isFalse();
        assertThat(Match.of("POST", "/a").fits("POST", "/b")).isFalse();
        assertThat(Match.of("POST", "/a").fits("POST", "/a")).isTrue();
    }
}
