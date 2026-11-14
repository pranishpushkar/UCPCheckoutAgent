package dev.ucplab.core.storage;

/**
 * A storage adapter's failure that is not a domain outcome: a busy database, a broken file, an exhausted pool. Core code
 * cannot see the adapters' own types, so they extend this one; RecoveryLoop logs it as the fixed reason
 * {@code database} (m6-plan section 12). The message is for the adapter's own diagnostics and is never logged by core.
 */
public abstract class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected StorageException(String message) {
        super(message);
    }

    protected StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
