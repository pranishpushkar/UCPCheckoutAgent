package dev.ucplab.core.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * What every {@link IdGenerator} must do (Appendix B, 7.4, 7.6, 7.8): never issue a blank id, never issue one twice, not
 * even from several threads, and, for a generator that claims UUIDs, issue the canonical UUID shape. Intent ids feed
 * the idempotency keys (7.4), so a repeated id would repeat a key.
 */
public abstract class IdGeneratorContract {

    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** A fresh generator. */
    protected abstract IdGenerator create();

    /** Whether this generator's ids are UUIDs (the production one is). */
    protected boolean claimsUuid() {
        return false;
    }

    // Covers: Appendix B (an id is never blank)
    @Test
    void next_isNeverBlank() {
        IdGenerator ids = create();

        for (int i = 0; i < 100; i++) {
            assertThat(ids.next()).isNotBlank();
        }
    }

    // Covers: Appendix B, 7.4, 7.6 (ten thousand ids from one generator are all different)
    @Test
    void next_isUniqueOverManyCalls() {
        IdGenerator ids = create();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(ids.next())).as("call " + i + " repeated an earlier id").isTrue();
        }
    }

    // Covers: Appendix B, 7.4, 7.8 (eight threads share one generator and never get the same id)
    @Test
    void next_isUniqueAcrossThreads() throws Exception {
        IdGenerator ids = create();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        int threads = 8;
        int each = 2_000;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    int repeats = 0;
                    for (int i = 0; i < each; i++) {
                        if (!seen.add(ids.next())) {
                            repeats++;
                        }
                    }
                    return repeats;
                }));
            }
            for (Future<Integer> f : results) {
                assertThat(f.get()).as("ids repeated by a thread").isZero();
            }
        }
        assertThat(seen).hasSize(threads * each);
    }

    // Covers: Appendix B (a generator that claims UUIDs issues the canonical lower-case 8-4-4-4-12 shape)
    @Test
    void next_ofAUuidGenerator_hasTheUuidShape() {
        Assumptions.assumeTrue(claimsUuid(), "this generator does not claim UUIDs");
        IdGenerator ids = create();

        for (int i = 0; i < 100; i++) {
            assertThat(ids.next()).matches(UUID_SHAPE);
        }
    }
}
