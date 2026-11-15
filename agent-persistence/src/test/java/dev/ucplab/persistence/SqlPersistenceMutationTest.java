package dev.ucplab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.identity.UserSession;
import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.DuplicateIntentException;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.MerchantId;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * H3 P9: how the SQL adapters tell a repeated key from any other failure of the database, and the migration history (7.3, 7.7,
 * 10, 23.4). Each test names the mutant it kills.
 */
class SqlPersistenceMutationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final UserId ALICE = new UserId("alice");

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(T0);
    private Path file;
    private Database db;
    private SqlIntentRepository intents;

    @BeforeEach
    void open() {
        file = dir.resolve("m.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
        intents = new SqlIntentRepository(db, clock);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private void exec(String sql) {
        db.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    private PurchaseIntent proposed(String id, String dedupe) {
        return PurchaseIntent.proposed(new IntentId(id), ALICE, new MerchantId("shop-1"), dedupe,
                List.of(new ProposedLine("item_123", 1)), "a@example.com", clock.instant());
    }

    // ---------------------------------------------------------------- SqlErrors

    // Covers: 7.7 (only a PRIMARY KEY or UNIQUE violation means "this key is taken"). Kills SqlErrors.isUniqueViolation:15
    // RemoveConditional_EQUAL_IF and :20 BooleanTrueReturnVals (NO_COVERAGE)
    @Test
    void onlyAPrimaryKeyOrUniqueViolation_isAUniqueViolation() {
        assertThat(SqlErrors.isUniqueViolation(new SQLiteException("dup", SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY))).isTrue();
        assertThat(SqlErrors.isUniqueViolation(new SQLiteException("dup", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE))).isTrue();
        assertThat(SqlErrors.isUniqueViolation(new SQLiteException("fk", SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY))).isFalse();
        assertThat(SqlErrors.isUniqueViolation(new SQLiteException("check", SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK))).isFalse();
        assertThat(SqlErrors.isUniqueViolation(new SQLiteException("busy", SQLiteErrorCode.SQLITE_BUSY))).isFalse();
        assertThat(SqlErrors.isUniqueViolation(new SQLException("other"))).isFalse();
    }

    // ---------------------------------------------------------------- a failure that is not a repeated key stays a failure

    // Covers: 7.7, 7.8. Kills SqlIntentRepository.lambda$insert$4:140 RemoveConditional_EQUAL_IF: a failing insert that is not a
    // repeated id or dedupe key is a database failure, not "an identical active proposal"
    @Test
    void anInsertThatFailsForAnotherReason_isNotADuplicate() {
        exec("CREATE TRIGGER refuse_intents BEFORE INSERT ON purchase_intent BEGIN SELECT RAISE(ABORT, 'boom'); END");

        assertThatThrownBy(() -> intents.insert(proposed("i1", "d1"))).isInstanceOf(DatabaseException.class)
                .isNotInstanceOf(DuplicateIntentException.class).hasRootCauseInstanceOf(SQLiteException.class);
        exec("DROP TRIGGER refuse_intents");
        intents.insert(proposed("i1", "d1"));
        assertThatThrownBy(() -> intents.insert(proposed("i2", "d1"))).isInstanceOf(DuplicateIntentException.class);
    }

    // Covers: 7.3 step 3, 7.8. Kills SqlIntentRepository.insertAttempt:546 RemoveConditional_EQUAL_IF: a failing attempt insert
    // that is not a repeated id is a database failure, not "attempt id already exists"; the intent write of the same
    // transaction is rolled back with it
    @Test
    void anAttemptInsertThatFailsForAnotherReason_isNotARepeatedId_andRollsBackTheIntent() {
        PurchaseIntent p = proposed("i1", "d1").withState(IntentState.QUOTING, clock.instant());
        intents.insert(p);
        PurchaseIntent claimed = intents.claimRecoverable("w1", java.time.Duration.ofSeconds(30), 10).get(0);
        exec("CREATE TRIGGER refuse_attempts BEFORE INSERT ON merchant_attempt BEGIN SELECT RAISE(ABORT, 'boom'); END");
        MerchantAttempt attempt = MerchantAttempt.sent(new AttemptId("a1"), p.id(), AttemptOp.CREATE, 0, Optional.empty(),
                Optional.empty(), clock.instant());

        assertThatThrownBy(() -> intents.recordAttempt(attempt, claimed.withCancelPending(true), claimed.version(), "w1"))
                .isInstanceOf(DatabaseException.class).isNotInstanceOf(IllegalArgumentException.class);

        PurchaseIntent after = intents.findForSystem(p.id()).orElseThrow();
        assertThat(after.version()).isEqualTo(claimed.version());
        assertThat(after.cancelPending()).isFalse();
        assertThat(intents.attempts(p.id())).isEmpty();
    }

    // Covers: 18.5. Kills SqlSessionRepository.lambda$insert$0:49 RemoveConditional_EQUAL_IF: a session insert that fails for another
    // reason than a repeated id is a database failure, not "a session with this id already exists"
    @Test
    void aSessionInsertThatFailsForAnotherReason_isNotARepeatedId() {
        exec("CREATE TRIGGER refuse_sessions BEFORE INSERT ON user_session BEGIN SELECT RAISE(ABORT, 'boom'); END");
        SqlSessionRepository sessions = new SqlSessionRepository(db, clock);

        assertThatThrownBy(() -> sessions.insert(UserSession.open("raw-cookie", ALICE, "csrf", T0.minusSeconds(5), T0)))
                .isInstanceOf(DatabaseException.class).isNotInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- the migration history

    // Covers: 23.4 /readyz. Kills Migrations.isCurrent:27, :28 and lambda$isCurrent$0:28 (every migration applied, and none of them
    // failed): a history row that says a migration failed is not current, whatever else is true
    @Test
    void aFailedMigrationInTheHistory_isNotCurrent() {
        Path other = dir.resolve("failed.db");
        Migrations.migrate(other);
        assertThat(Migrations.isCurrent(other)).isTrue();
        try (Database d = Database.open(other, 1)) {
            d.write(c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("UPDATE flyway_schema_history SET success = 0 WHERE version = '7'");
                }
                return null;
            });
        }

        assertThat(Migrations.isCurrent(other)).isFalse();
    }
}
