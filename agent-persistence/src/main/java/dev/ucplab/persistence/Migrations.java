package dev.ucplab.persistence;

import java.nio.file.Path;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteDataSource;

/** Applies the Flyway migrations in {@code db/migration} to the database file (section 10). */
public final class Migrations {

    private Migrations() {
    }

    /** Idempotent: an up-to-date file is left alone. Run once at startup, before {@link Database#open}. */
    public static void migrate(Path file) {
        Objects.requireNonNull(file, "file");
        SQLiteDataSource source = new SQLiteDataSource(Database.sqliteConfig());
        source.setUrl(Database.urlOf(file));
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
