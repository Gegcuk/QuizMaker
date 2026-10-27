package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptLifecycleService;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.LifecycleStateRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.LifecycleStateRepository.StoredState;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleStateException.Reason.*;

@Service
public class AttemptLifecycleServiceImpl implements AttemptLifecycleService {
    private final LifecycleStateRepository repository;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final TransactionTemplate mandatory;

    public AttemptLifecycleServiceImpl(LifecycleStateRepository repository, PlatformTransactionManager transactions,
                                       Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.clock = clock;
        this.metrics = metrics;
        this.write = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
        this.mandatory = new TransactionTemplate(transactions);
        this.mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    public LifecycleState initialize(UUID attemptId) {
        return execute("initialize", write, () -> {
            var value = LifecycleState.initial(attemptId, clock.instant());
            repository.insert(value);
            return value;
        });
    }

    @Override
    public LifecycleRead read(LifecycleReference reference) {
        if (reference == LifecycleReference.Legacy.NO_TARGET_STATE) {
            record("read", "legacy", null);
            return LifecycleRead.Legacy.NO_TARGET_STATE;
        }
        return execute("read", read, () -> {
            if (!(reference instanceof LifecycleReference.Target target)) throw new LifecycleStateException(INVALID_INPUT);
            return verify(repository.findById(target.attemptId()).orElseThrow(() -> new LifecycleStateException(STATE_UNAVAILABLE)));
        });
    }

    @Override
    public LifecycleState lock(UUID attemptId) {
        return execute("lock", mandatory, () -> locked(attemptId));
    }

    @Override
    public LifecycleState save(UUID attemptId, long expectedVersion, AttemptLifecycle.State next) {
        return execute("save", write, () -> {
            if (expectedVersion < 0 || next == null) throw new LifecycleStateException(INVALID_INPUT);
            var current = locked(attemptId);
            if (current.rowVersion() != expectedVersion) throw new LifecycleStateException(STATE_CONFLICT);
            var value = current.advance(next, clock.instant());
            if (!repository.compareAndSet(expectedVersion, value)) throw new LifecycleStateException(STATE_CONFLICT);
            return value;
        });
    }

    private LifecycleState locked(UUID attemptId) {
        if (attemptId == null) throw new LifecycleStateException(INVALID_INPUT);
        return verify(repository.findByIdForUpdate(attemptId).orElseThrow(() -> new LifecycleStateException(STATE_UNAVAILABLE)));
    }

    private static LifecycleState verify(StoredState row) {
        if (row.lifecycleVersion() != AttemptLifecycle.VERSION) throw new LifecycleStateException(UNSUPPORTED_VERSION);
        try {
            var state = new AttemptLifecycle.State(row.lifecycleVersion(),
                    AttemptLifecycle.parseValue(row.lifecycleVersion(), AttemptLifecycle.Execution.class, row.execution()),
                    AttemptLifecycle.parseValue(row.lifecycleVersion(), AttemptLifecycle.AnswerCollection.class, row.answers()));
            return new LifecycleState(row.attemptId(), row.rowVersion(), state, row.createdAt(), row.updatedAt(),
                    row.startedAt(), row.submittedAt());
        } catch (LifecycleException | LifecycleStateException failure) {
            throw new LifecycleStateException(CORRUPT_STATE);
        }
    }

    private <T> T execute(String operation, TransactionTemplate transaction, Supplier<T> action) {
        var sample = Timer.start();
        String outcome = "persistence_unavailable";
        try {
            T result = transaction.execute(status -> action.get());
            outcome = "returned";
            return result;
        } catch (LifecycleStateException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (DuplicateKeyException failure) {
            outcome = "state_conflict";
            throw new LifecycleStateException(STATE_CONFLICT);
        } catch (DataAccessException | TransactionException failure) {
            throw new LifecycleStateException(PERSISTENCE_UNAVAILABLE);
        } finally {
            record(operation, outcome, sample);
        }
    }

    private void record(String operation, String outcome, Timer.Sample sample) {
        try {
            var timer = Timer.builder("attempt.lifecycle.storage").tags("version", "1", "operation", operation,
                    "outcome", outcome).register(metrics);
            if (sample == null) timer.record(java.time.Duration.ZERO); else sample.stop(timer);
        } catch (RuntimeException ignored) {
            // Optional telemetry must not replace the result or affect a caller's transaction.
        }
    }
}
