package dev.ucplab.core.id;

import dev.ucplab.core.MutableClock;
import java.time.Instant;
import java.util.SplittableRandom;

/** {@link UuidV7IdGenerator} runs the id generator contract (H3 P4; Appendix B, 7.4, 7.6), on a clock that never moves. */
class UuidV7IdGeneratorContractTest extends IdGeneratorContract {

    @Override
    protected IdGenerator create() {
        return new UuidV7IdGenerator(new MutableClock(Instant.parse("2026-11-12T10:00:00.123Z")), new SplittableRandom(7));
    }

    @Override
    protected boolean claimsUuid() {
        return true;
    }
}
