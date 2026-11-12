package dev.ucplab.persistence;

/**
 * A test seam inside the two-statement writes of {@link SqlIntentRepository}. A test passes one that throws at a named
 * point to show the transaction rolls back and neither change is visible (crash-matrix row 20, 7.3 steps 3 and 5). The
 * real JVM kill is M4. Production code uses {@link #NONE}.
 */
@FunctionalInterface
public interface Failpoint {

    /** In recordAttempt: after the intent row is updated, before the attempt row is inserted. */
    String RECORD_ATTEMPT_BETWEEN = "recordAttempt:between";
    /** In recordAttempt: after both rows are written, before the commit. */
    String RECORD_ATTEMPT_AFTER = "recordAttempt:after";
    /** In resolveAttempt (with intent): after the intent row is updated, before the attempt row is updated. */
    String RESOLVE_ATTEMPT_BETWEEN = "resolveAttempt:between";
    /** In resolveAttempt (with intent): after both rows are written, before the commit. */
    String RESOLVE_ATTEMPT_AFTER = "resolveAttempt:after";

    Failpoint NONE = point -> { };

    void hit(String point);
}
