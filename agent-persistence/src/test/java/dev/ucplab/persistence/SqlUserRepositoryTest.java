package dev.ucplab.persistence;

import dev.ucplab.core.identity.UserRepository;
import dev.ucplab.core.identity.UserRepositoryContract;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The shared {@code app_user} contract (section 10, 18.2) against SQLite. */
class SqlUserRepositoryTest extends UserRepositoryContract {

    @TempDir
    Path dir;

    private Database db;

    @Override
    protected UserRepository create() {
        Path file = dir.resolve("users.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        return new SqlUserRepository(db);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
