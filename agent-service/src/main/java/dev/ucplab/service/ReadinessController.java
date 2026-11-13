package dev.ucplab.service;

import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Migrations;
import dev.ucplab.service.config.ServiceProperties;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /readyz} (23.4): 200 once the database answers and every migration is applied, else 503 with a fixed
 * reason. Never a message from the database, which could name a path.
 */
@RestController
class ReadinessController {

    private final Database db;
    private final Path file;

    @Autowired
    ReadinessController(Database db, ServiceProperties properties) {
        this(db, properties.databaseFile());
    }

    ReadinessController(Database db, Path file) {
        this.db = Objects.requireNonNull(db, "db");
        this.file = Objects.requireNonNull(file, "file");
    }

    @GetMapping("/readyz")
    ResponseEntity<Map<String, String>> readyz() {
        try {
            db.read(c -> {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT 1")) {
                    return r.next();
                }
            });
        } catch (RuntimeException e) {
            return notReady("database");
        }
        boolean current;
        try {
            current = Migrations.isCurrent(file);
        } catch (RuntimeException e) {
            return notReady("database");
        }
        if (!current) {
            return notReady("migrations_pending");
        }
        return ResponseEntity.ok(Map.of("status", "ready"));
    }

    private static ResponseEntity<Map<String, String>> notReady(String reason) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "not_ready", "reason", reason));
    }
}
