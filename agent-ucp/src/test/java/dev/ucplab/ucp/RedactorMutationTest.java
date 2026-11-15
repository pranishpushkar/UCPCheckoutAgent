package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** H3 P8: the credential scrubbing of I8 and NFR6, one test per mutant it kills (see each comment). */
class RedactorMutationTest {

    // Covers: I8, NFR6. Kills Redactor.lambda$new$0:26 RemoveConditional_EQUAL_IF (x2) and BooleanTrueReturnVals: a null or
    // blank "secret" is no secret; if it were kept, replacing it would mask every gap in the text (or fail on null)
    @Test
    void aNullOrBlankSecret_isIgnored_andARealOneIsStillMasked() {
        Redactor redactor = new Redactor(Arrays.asList("", "  ", null, "tok_1"));

        assertThat(redactor.scrub("a  b tok_1 c")).isEqualTo("a  b [redacted] c");
    }

    // Covers: I8, NFR6. Kills Redactor.scrub:39 RemoveConditional_EQUAL_ELSE: a NotSent cause that carries the token or an
    // Authorization header is scrubbed (the cause of a failed call goes into the write-ahead log)
    @Test
    void aNotSentCause_isScrubbed() {
        Redactor redactor = new Redactor(List.of("tok_1"));
        CallOutcome<CheckoutView> out = redactor.scrub(new CallOutcome.NotSent<>("refused tok_1; Authorization: Bearer abc.def"));

        assertThat(out).isEqualTo(new CallOutcome.NotSent<CheckoutView>("refused [redacted]; Authorization: [redacted]"));
    }

    // Covers: I8, NFR6. Kills Redactor.scrub:41 RemoveConditional_EQUAL_ELSE (the Uncertain twin of the test above)
    @Test
    void anUncertainCause_isScrubbed() {
        Redactor redactor = new Redactor(List.of("tok_1"));
        CallOutcome<CheckoutView> out = redactor.scrub(new CallOutcome.Uncertain<>("reset after tok_1 was sent"));

        assertThat(out).isEqualTo(new CallOutcome.Uncertain<CheckoutView>("reset after [redacted] was sent"));
    }

    // Covers: I8. Shows that Redactor.changed:63 (RemoveConditional_EQUAL_IF, BooleanTrueReturnVals) and scrub:39, :41
    // RemoveConditional_EQUAL_IF (always rebuild) are equivalent: a cause with nothing to scrub comes back equal, whether it
    // is the same object or a copy
    @Test
    void aCleanCause_comesBackEqual() {
        Redactor redactor = new Redactor(List.of("tok_1"));

        assertThat(redactor.scrub(new CallOutcome.NotSent<CheckoutView>("dns failed"))).isEqualTo(new CallOutcome.NotSent<CheckoutView>("dns failed"));
        assertThat(redactor.scrub(new CallOutcome.Uncertain<CheckoutView>("timed out"))).isEqualTo(new CallOutcome.Uncertain<CheckoutView>("timed out"));
    }
}
