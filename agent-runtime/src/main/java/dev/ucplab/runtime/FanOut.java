package dev.ucplab.runtime;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.Transport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * One catalog call asked of every active merchant at once (DESIGN 6.2, 19), shared by {@code search_catalog} and
 * {@code get_product}. Each merchant is asked on a virtual thread, and all are awaited from one start until its limit has
 * passed: {@link ToolPorts#catalogSource()} for a catalog source (26.7), {@link ToolPorts#perMerchant()} for any other.
 * The start is shared, so the whole call takes at most the longest limit, never the sum; each merchant is awaited by its
 * own deadline, the earliest first, so an answer after a merchant's own limit is never kept. A merchant that is not done by then is cancelled with an interrupt, which ends its HTTP call (the transport turns
 * it into one failed attempt and the retry loop does not try again, so it counts once against the merchant's breaker), and
 * its answer is empty, like that of a merchant with no catalog client. A catalog that throws (a bug: failures are
 * outcomes) fails the tool, as it always did. Nothing here holds a lock; the pool is shut down without waiting, so a call
 * that ignores the interrupt cannot hold the tool.
 */
final class FanOut {

    private FanOut() {
    }

    /** The outcome of each merchant in {@code active}'s order, whatever order they finished in; empty if it was not had. */
    static <T> List<Optional<CallOutcome<T>>> ask(ToolPorts ports, List<MerchantEntry> active,
                                                  Function<CatalogGateway, CallOutcome<T>> call) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<CallOutcome<T>>> futures = new ArrayList<>();
            for (MerchantEntry merchant : active) {
                CatalogGateway catalog = ports.catalogs().get(merchant.id());
                futures.add(catalog == null ? null : pool.submit(() -> call.apply(catalog)));
            }
            long started = System.nanoTime();
            // Awaited in deadline order, so each merchant is held to its own limit: a store is cancelled at the store
            // limit even while a catalog source's longer one runs, and its later answer is not kept.
            List<Integer> byDeadline = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                byDeadline.add(i);
            }
            byDeadline.sort(Comparator.comparing(i -> limit(ports, active.get(i))));
            List<Optional<CallOutcome<T>>> answers = new ArrayList<>(Collections.nCopies(futures.size(), Optional.empty()));
            for (int i : byDeadline) {
                Future<CallOutcome<T>> future = futures.get(i);
                if (future != null) {
                    answers.set(i, await(future, started + limit(ports, active.get(i)).toNanos()));
                }
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 6.2, 26.7: a catalog source's limit, or a store's. */
    private static Duration limit(ToolPorts ports, MerchantEntry merchant) {
        return merchant.transport() == Transport.CATALOG ? ports.catalogSource() : ports.perMerchant();
    }

    private static <T> Optional<CallOutcome<T>> await(Future<CallOutcome<T>> future, long deadline) {
        try {
            return Optional.of(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            return Optional.empty();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Optional.empty();
        }
    }
}
