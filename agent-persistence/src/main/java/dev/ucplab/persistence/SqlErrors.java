package dev.ucplab.persistence;

import java.sql.SQLException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/** The one place that reads driver error codes. */
final class SqlErrors {

    private SqlErrors() {
    }

    /** A PRIMARY KEY or UNIQUE constraint (including a unique index) refused the write. */
    static boolean isUniqueViolation(SQLException e) {
        if (e instanceof SQLiteException s) {
            SQLiteErrorCode code = s.getResultCode();
            return code == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY
                    || code == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE;
        }
        return false;
    }
}
