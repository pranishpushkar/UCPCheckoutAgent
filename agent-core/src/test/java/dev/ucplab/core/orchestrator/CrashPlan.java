package dev.ucplab.core.orchestrator;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which calls of one decorated port crash the process, and when. A crash is armed for the n-th call of an operation
 * (counted from the plan's creation, so a fresh process starts at zero) and fires once, before the call reaches the
 * delegate or right after it returns (7.3 crash points). It throws {@link InterceptingGateway.SimulatedCrash}, an
 * {@code Error} like a kill -9, so none of the caller's later writes happen.
 */
final class CrashPlan<O extends Enum<O>> {

    enum Phase { BEFORE, AFTER }

    private record Armed<O>(O op, Phase phase, int nth) {}

    private final Map<O, Integer> calls;
    private final List<Armed<O>> armed = new ArrayList<>();
    private boolean fired;

    CrashPlan(Class<O> ops) {
        this.calls = new EnumMap<>(ops);
    }

    void arm(O op, Phase phase, int nth) {
        if (nth < 1) {
            throw new IllegalArgumentException("nth starts at 1: " + nth);
        }
        armed.add(new Armed<>(op, phase, nth));
    }

    /** Arms the next call of {@code op} counted from now. */
    void armNext(O op, Phase phase) {
        arm(op, phase, calls.getOrDefault(op, 0) + 1);
    }

    void before(O op) {
        int n = calls.merge(op, 1, Integer::sum);
        fire(op, Phase.BEFORE, n);
    }

    void after(O op) {
        fire(op, Phase.AFTER, calls.getOrDefault(op, 0));
    }

    /** Whether any armed crash has fired. */
    boolean fired() {
        return fired;
    }

    /** How many times {@code op} was called, crashed calls included. */
    int calls(O op) {
        return calls.getOrDefault(op, 0);
    }

    private void fire(O op, Phase phase, int n) {
        if (armed.removeIf(a -> a.op() == op && a.phase() == phase && a.nth() == n)) {
            fired = true;
            throw new InterceptingGateway.SimulatedCrash(phase.name().toLowerCase() + " " + op + " #" + n);
        }
    }
}
