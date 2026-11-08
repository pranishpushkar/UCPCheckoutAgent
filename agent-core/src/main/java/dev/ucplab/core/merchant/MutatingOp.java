package dev.ucplab.core.merchant;

/** The {@code op} of an idempotency key (7.4): one of create, update, complete, cancel. */
public enum MutatingOp {
    CREATE("create"),
    UPDATE("update"),
    COMPLETE("complete"),
    CANCEL("cancel");

    private final String wireName;

    MutatingOp(String wireName) {
        this.wireName = wireName;
    }

    /** The lowercase name hashed into the key. */
    public String wireName() {
        return wireName;
    }
}
