package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.SessionRepository;
import dev.ucplab.core.identity.SessionRepositoryContract;
import dev.ucplab.core.identity.UserSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shared session contract (18.5) against SQLite, plus a scan of the file for the raw cookie value. */
class SqlSessionRepositoryTest extends SessionRepositoryContract {

    @TempDir
    Path dir;

    private Database db;

    @Override
    protected SessionRepository create(Clock clock) {
        Path file = dir.resolve("sessions.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        return new SqlSessionRepository(db, clock);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }

    private String bytesOnDisk() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                all.append(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1));
            }
        }
        return all.toString();
    }

    // Covers: 18.5, I8 (the raw session id never reaches the database file; its hash does, which is the positive
    // control that the scan reads what was written)
    @Test
    void rawSessionId_isNeverOnDisk() throws IOException {
        String canary = "CANARY-SESSION-" + Long.toHexString(System.nanoTime());
        // Not the contract's session(...): its CSRF token embeds the raw id, and the CSRF token is stored (18.5).
        UserSession s = UserSession.open(canary, ALICE, "csrf-token-without-the-id", T0.minusSeconds(5), T0);
        repo.insert(s);
        repo.touch(canary);
        repo.findLive(canary);
        repo.delete("CANARY-SESSION-other-never-stored");
        db.close();

        String disk = bytesOnDisk();
        assertThat(disk).contains(s.idHash());
        assertThat(disk).contains("csrf-token-without-the-id");
        assertThat(disk).doesNotContain("CANARY-SESSION-");
        db = Database.open(dir.resolve("sessions.db"), 1);
    }
}
