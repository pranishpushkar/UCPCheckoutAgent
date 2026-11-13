package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Migrations;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code GET /readyz} (23.4): 503 until the migrations are current, then 200. */
class ReadinessControllerTest {

    @TempDir
    Path dir;

    // Covers: 23.4 (/readyz is 503 migrations_pending on a database that has not been migrated, 200 once it has)
    @Test
    void readyz_beforeMigrations_is503_thenAfter_is200() {
        Path file = dir.resolve("r.db");
        try (Database db = Database.open(file, 1)) {
            ReadinessController readiness = new ReadinessController(db, file);

            var before = readiness.readyz();
            Migrations.migrate(file);
            var after = readiness.readyz();

            assertThat(before.getStatusCode().value()).isEqualTo(503);
            assertThat(before.getBody()).containsEntry("status", "not_ready").containsEntry("reason",
                    "migrations_pending");
            assertThat(after.getStatusCode().value()).isEqualTo(200);
            assertThat(after.getBody()).containsEntry("status", "ready");
        }
    }

    // Covers: 23.4 (a closed database is 503 database, with no message from the driver)
    @Test
    void readyz_databaseUnavailable_is503() {
        Path file = dir.resolve("closed.db");
        Migrations.migrate(file);
        Database db = Database.open(file, 1);
        db.close();

        var response = new ReadinessController(db, file).readyz();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("reason", "database").hasSize(2);
    }
}
