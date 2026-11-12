package dev.ucplab.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/** One unit of JDBC work on a borrowed connection. It must not call the merchant or any other network service. */
@FunctionalInterface
public interface SqlWork<T> {

    T run(Connection connection) throws SQLException;
}
