package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PortableSqlLintTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    // Covers: section 10, ADR 0002 (every migration stays in the portable subset: no AUTOINCREMENT and no other
    // SQLite-only syntax)
    @Test
    void migrations_useOnlyPortableSql() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(MIGRATIONS)) {
            files = s.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }

        assertThat(files).as("migrations found under " + MIGRATIONS.toAbsolutePath()).isNotEmpty();
        for (Path file : files) {
            assertThat(PortableSql.violations(Files.readString(file))).as(file.getFileName().toString()).isEmpty();
        }
    }

    // Covers: section 10 (the lint is not vacuous: each kind of SQLite-only syntax is caught)
    @Test
    void lint_flagsSqliteOnlySyntax() {
        List<String> bad = List.of(
                "CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)",
                "CREATE TABLE t (id TEXT PRIMARY KEY) WITHOUT ROWID",
                "CREATE TABLE t (id TEXT) STRICT",
                "SELECT rowid FROM t",
                "CREATE TABLE t (id INTEGER PRIMARY KEY)",
                "INSERT OR REPLACE INTO t VALUES (1)",
                "REPLACE INTO t VALUES (1)",
                "PRAGMA foreign_keys = ON",
                "SELECT IFNULL(a, 1) FROM t",
                "SELECT strftime('%s', x) FROM t",
                "SELECT datetime('now')",
                "SELECT * FROM t WHERE a GLOB 'x*'",
                "SELECT `a` FROM t",
                "VACUUM");

        for (String sql : bad) {
            assertThat(PortableSql.violations(sql)).as(sql).isNotEmpty();
        }
    }

    // Covers: section 10 (portable syntax passes, and a comment that names a banned word is not code)
    @Test
    void lint_acceptsPortableSqlAndIgnoresComments() {
        String ok = """
                -- no AUTOINCREMENT here, and no PRAGMA
                /* WITHOUT ROWID */
                CREATE TABLE t (id TEXT PRIMARY KEY, seq INTEGER NOT NULL, UNIQUE (id, seq));
                CREATE UNIQUE INDEX ux ON t(id) WHERE seq NOT IN (1, 2);
                SELECT COALESCE(MAX(seq), 0) + 1 FROM t;
                """;

        assertThat(PortableSql.violations(ok)).isEmpty();
    }
}
