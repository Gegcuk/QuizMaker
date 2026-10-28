package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.ExecutionTraceService;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException;
import uk.gegc.quizmaker.features.attempt.domain.repository.ExecutionTraceRepository;

import java.time.Clock;
import java.util.Locale;

import static uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.VERSION;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException.require;

@Service
public class ExecutionTraceServiceImpl implements ExecutionTraceService {
    private final ExecutionTraceRepository repository;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate mandatory;

    public ExecutionTraceServiceImpl(ExecutionTraceRepository repository, PlatformTransactionManager transactions,
                                    Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.clock = clock;
        this.metrics = metrics;
        mandatory = new TransactionTemplate(transactions);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    public Appended append(Scope scope, Event event) {
        var sample = Timer.start();
        String outcome = "persistence_unavailable";
        try {
            require(TransactionSynchronizationManager.isActualTransactionActive(), TRANSACTION_REQUIRED);
            var result = mandatory.execute(status -> {
                require(scope != null && event != null, INVALID_INPUT);
                repository.ensureStream(scope);
                var stream = repository.lockStream(scope.attemptId()).orElseThrow(() -> new TraceException(CONTEXT_UNAVAILABLE));
                require(stream.version() == VERSION, UNSUPPORTED_VERSION);
                require(scope.equals(stream.scope()), EVENT_CONFLICT);
                require(stream.lastSequence() >= 0, CORRUPT_TRACE);
                if (stream.lastSequence() > 0) {
                    var last = repository.last(scope.attemptId());
                    require(last.isPresent() && last.get().sequence() == stream.lastSequence(), CORRUPT_TRACE);
                }
                var identity = repository.reserve(scope.attemptId(), event.id());
                require(identity.attemptId().equals(scope.attemptId()), EVENT_CONFLICT);
                require(identity.sequence() >= 0, CORRUPT_TRACE);
                if (identity.sequence() > 0) {
                    var saved = repository.findEvent(event.id()).orElseThrow(() -> new TraceException(CORRUPT_TRACE));
                    require(saved.scope().equals(scope) && saved.event().equals(event), EVENT_CONFLICT);
                    require(saved.sequence() == identity.sequence() && saved.sequence() <= stream.lastSequence(), CORRUPT_TRACE);
                    return new Appended(saved, true);
                }
                require(stream.lastSequence() < Long.MAX_VALUE, CORRUPT_TRACE);
                var saved = new Stored(scope, stream.lastSequence() + 1, clock.instant(), event);
                repository.insert(saved);
                require(repository.assign(event.id(), saved.sequence()), CORRUPT_TRACE);
                require(repository.advance(scope.attemptId(), stream.lastSequence()), CORRUPT_TRACE);
                return new Appended(saved, false);
            });
            outcome = result.replayed() ? "replay_returned" : "append_returned";
            return result;
        } catch (TraceException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (DataIntegrityViolationException failure) {
            outcome = "event_conflict";
            throw new TraceException(EVENT_CONFLICT);
        } catch (DataAccessException | TransactionException failure) {
            throw new TraceException(PERSISTENCE_UNAVAILABLE);
        } finally {
            try {
                sample.stop(Timer.builder("attempt.trace.append").tags("version", "1", "type",
                        event == null ? "invalid" : event.type().name().toLowerCase(Locale.ROOT), "outcome", outcome).register(metrics));
            } catch (RuntimeException ignored) {
                // Optional telemetry must not replace the result or affect the caller transaction.
            }
        }
    }
}
