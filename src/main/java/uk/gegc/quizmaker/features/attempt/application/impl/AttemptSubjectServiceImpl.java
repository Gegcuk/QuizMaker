package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptSubjectService;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectException;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectLookup;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubjectRepository;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectException.Reason.*;

@Service
public class AttemptSubjectServiceImpl implements AttemptSubjectService {
    private final AttemptSubjectRepository repository;
    private final MeterRegistry metrics;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public AttemptSubjectServiceImpl(AttemptSubjectRepository repository,
                                     PlatformTransactionManager transactions, MeterRegistry metrics) {
        this.repository = repository;
        this.metrics = metrics;
        this.write = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    @Override
    public AttemptSubject bindOrReplay(UUID trustedUserId, AttemptSubject subject) {
        return execute("bind_or_replay", write, () -> {
            if (trustedUserId == null) throw new AttemptSubjectException(SUBJECT_UNAVAILABLE);
            if (subject == null) throw new AttemptSubjectException(INVALID_SUBJECT);
            if (!trustedUserId.equals(subject.userId())) throw new AttemptSubjectException(SUBJECT_UNAVAILABLE);
            return repository.bindOrReplay(trustedUserId, subject);
        });
    }

    @Override
    public AttemptSubjectLookup read(UUID attemptId) {
        return execute("read", read, () -> {
            if (attemptId == null) throw new AttemptSubjectException(INVALID_SUBJECT);
            return repository.read(attemptId);
        });
    }

    private <T> T execute(String operation, TransactionTemplate transaction, Supplier<T> action) {
        var sample = startTimer();
        String outcome = "persistence_unavailable";
        try {
            T result = transaction.execute(status -> action.get());
            outcome = result instanceof AttemptSubjectLookup.LegacyOrUnbound ? "legacy_or_unbound" : "success";
            return result;
        } catch (AttemptSubjectException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            throw new AttemptSubjectException(PERSISTENCE_UNAVAILABLE);
        } finally {
            recordTimer(sample, operation, outcome);
        }
    }

    private Timer.Sample startTimer() {
        try { return Timer.start(metrics); }
        catch (RuntimeException ignored) {
            // Telemetry must not prevent a command from reaching its transaction boundary.
            return null;
        }
    }

    private void recordTimer(Timer.Sample sample, String operation, String outcome) {
        try {
            if (sample != null) sample.stop(Timer.builder("quizmaker.attempt.subject.operations")
                    .tags("operation", operation, "outcome", outcome).register(metrics));
        } catch (RuntimeException ignored) {
            // Preserve a committed result and the original domain/persistence failure alike.
        }
    }
}
