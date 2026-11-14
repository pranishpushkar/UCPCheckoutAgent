package dev.ucplab.persistence;

import dev.ucplab.core.storage.StorageException;

/** A JDBC failure that is not a domain outcome: a busy database, a broken file, an exhausted pool. */
public final class DatabaseException extends StorageException {

    private static final long serialVersionUID = 1L;

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(String message) {
        super(message);
    }
}
