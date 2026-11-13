package dev.ucplab.persistence;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.sqlite.SQLiteDataSource;

/** Applies the Flyway migrations in {@code db/migration} to the database file (section 10). */
public final class Migrations {

    private Migrations() {
    }

    /** Idempotent: an up-to-date file is left alone. Run once at startup, before {@link Database#open}. */
    public static void migrate(Path file) {
        flyway(file).migrate();
    }

    /**
     * True when every migration on the classpath has been applied to the file and none failed (23.4 {@code /readyz}).
     * Reads Flyway's history only; never migrates.
     */
    public static boolean isCurrent(Path file) {
        MigrationInfoService info = flyway(file).info();
        return info.pending().length == 0 && info.current() != null
                && Arrays.stream(info.applied()).allMatch(m -> m.getState().isApplied() && !m.getState().isFailed());
    }

    private static Flyway flyway(Path file) {
        Objects.requireNonNull(file, "file");
        SQLiteDataSource source = new SQLiteDataSource(Database.sqliteConfig());
        source.setUrl(Database.urlOf(file));
        return Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .load();
    }
}
