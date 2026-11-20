package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.learning.config.LearningCommandRateConfig;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningCommandBudget;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLearningCommandBudget implements LearningCommandBudget {
    // One token is 60 million units: each elapsed microsecond earns exactly perMinute units.
    private static final long TOKEN = 60_000_000L;
    // Even at the lowest supported refill rate (1/minute) all balances are full by this age.
    private static final long MAX_REFILL_MICROS = TOKEN * LearningCommandRateConfig.MAX_BURST;
    private record Balance(long credit, long accountedAt) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final LearningCommandRateConfig config;

    // The existing database-backed admission clock gives all application replicas one timeline.
    public JdbcLearningCommandBudget(JdbcTemplate jdbc, @Qualifier("authAdmissionClock") Clock clock,
                                    LearningCommandRateConfig config) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.config = config;
    }

    @Override
    public void chargeIfNew(UUID trustedLearnerId, BooleanSupplier recorded) {
        require(trustedLearnerId != null && recorded != null, INVALID_INPUT);
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_READ_COMMITTED), UNSUPPORTED_TRANSACTION);
        try {
            if (!recorded.getAsBoolean()) chargeUnderLock(trustedLearnerId.toString(), recorded);
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        }
    }

    private void chargeUnderLock(String learner, BooleanSupplier recorded) {
        int refill = config.getPerMinute();
        long capacity = TOKEN * config.getBurst();
        // A competing first command waits on the same unique learner row as all later commands.
        jdbc.update("""
                INSERT INTO learning_command_budget(learner_id,credit_units,accounted_us)
                VALUES(UUID_TO_BIN(?),?,?) ON DUPLICATE KEY UPDATE learner_id=learner_id
                """, learner, capacity, nowMicros());
        Balance saved = jdbc.queryForObject("""
                SELECT credit_units,accounted_us FROM learning_command_budget
                WHERE learner_id=UUID_TO_BIN(?) FOR UPDATE
                """, (row, index) -> new Balance(row.getLong(1), row.getLong(2)), learner);
        // A peer may have committed this key while we waited. The domain writer still validates
        // the full saved input and receipt, even if this learner has no available credit.
        if (recorded.getAsBoolean()) return;
        long now = nowMicros(); // Sample after any wait, while holding the row.
        long elapsed = Math.min(MAX_REFILL_MICROS, Math.max(0, now - saved.accountedAt()));
        long credit = Math.min(capacity, saved.credit() + elapsed * refill);
        if (credit < TOKEN) {
            long refillMicros = (TOKEN - credit + refill - 1) / refill;
            long waitMicros = Math.max(0, saved.accountedAt() - now) + refillMicros;
            throw new RateLimitExceededException("Learning command rate exceeded", (waitMicros + 999_999) / 1_000_000);
        }
        jdbc.update("""
                UPDATE learning_command_budget SET credit_units=?,accounted_us=? WHERE learner_id=UUID_TO_BIN(?)
                """, credit - TOKEN, Math.max(now, saved.accountedAt()), learner);
        // At most one new row per success; recycle at most one fully idle row. Skip active peers.
        var expired = jdbc.query("""
                SELECT learner_id FROM learning_command_budget WHERE accounted_us<=?
                ORDER BY accounted_us LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, index) -> row.getBytes(1), now - MAX_REFILL_MICROS);
        if (!expired.isEmpty()) {
            jdbc.update("DELETE FROM learning_command_budget WHERE learner_id=?", expired.get(0));
        }
    }

    private long nowMicros() {
        return ChronoUnit.MICROS.between(Instant.EPOCH, clock.instant());
    }
}
