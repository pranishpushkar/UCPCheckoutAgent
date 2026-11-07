package dev.ucplab.core.intent;

/** The {@code op} column of {@code merchant_attempt} (section 10): the four mutating operations and get. */
public enum AttemptOp {
    CREATE, UPDATE, COMPLETE, CANCEL, GET
}
