package dev.ucplab.persistence;

/** A JDBC failure that is not a domain outcome: a busy database, a broken file, an exhausted pool. */
public final class DatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(String message) {
        super(message);
    }
}
