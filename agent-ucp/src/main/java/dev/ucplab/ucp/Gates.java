package dev.ucplab.ucp;

import dev.ucplab.core.merchant.CallOutcome;
import java.util.List;
import java.util.Objects;

/** Combines gates (8.3). */
public final class Gates {

    private Gates() {
    }

    /**
     * A gate that admits an attempt only if every gate in {@code gates} does, asked in the order given and stopping at the
     * first refusal; every admitted attempt's outcome goes to every gate. Put the rate limiter before the breaker: a
     * refused token then never uses up the breaker's single half-open probe.
     */
    public static Gate all(Gate... gates) {
        List<Gate> list = List.of(Objects.requireNonNull(gates, "gates"));
        return new Gate() {
            @Override
            public boolean tryAcquire() {
                for (Gate g : list) {
                    if (!g.tryAcquire()) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void record(CallOutcome<?> outcome) {
                list.forEach(g -> g.record(outcome));
            }

            @Override
            public void recordReplied() {
                list.forEach(Gate::recordReplied);
            }
        };
    }
}
