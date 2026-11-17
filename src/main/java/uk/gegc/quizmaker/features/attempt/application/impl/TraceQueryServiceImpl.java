package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Locale;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQuery;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQuery.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException;
import uk.gegc.quizmaker.features.attempt.domain.repository.TraceQueryRepository;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException.Reason.*;

@Service
@PreAuthorize("isAuthenticated()")
public class TraceQueryServiceImpl implements TraceQueryService {
    private final TraceQueryRepository repository;
    private final List<TraceQueryContextReader> contexts;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;

    public TraceQueryServiceImpl(TraceQueryRepository repository, List<TraceQueryContextReader> contexts,
                                 PlatformTransactionManager manager, MeterRegistry metrics) {
        this.repository = repository; this.contexts = List.copyOf(contexts); this.metrics = metrics;
        transaction = AttemptTransactions.readCommitted(manager); transaction.setReadOnly(true);
    }

    @Override public Page read(Request request) {
        AttemptTransactions.requireCompatible(transaction.getTransactionManager(), false);
        var sample = Timer.start(); String outcome = "persistence_unavailable";
        try {
            var result = transaction.execute(status -> {
                try { return authorized(request); }
                catch (RuntimeException failure) { throw sanitized(failure); }
            });
            outcome = "read"; return result;
        } catch (RuntimeException failure) {
            var safe = sanitized(failure); outcome = safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { sample.stop(Timer.builder("attempt.trace.query").tags("version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* Optional telemetry cannot affect disclosure or caller state. */ }
        }
    }

    private Page authorized(Request request) {
        require(request != null && request.attemptId() != null && request.purpose() != null
                && request.limit() > 0 && request.limit() <= ExecutionTrace.MAX_PAGE_SIZE, INVALID_QUERY);
        require(request.version() == TraceQuery.VERSION, UNSUPPORTED_VERSION);
        require(request.cursor() == null || request.cursor().length() == 66, INVALID_CURSOR);
        require(contexts.size() == 1, ACCESS_UNAVAILABLE);
        Context context;
        try {
            var decision = contexts.get(0).authorize(request.attemptId(), request.purpose(), Operation.READ_PAGE);
            require(decision != null && decision.isPresent(), ACCESS_UNAVAILABLE);
            context = decision.get();
            require(context.scope() != null && context.scope().attemptId().equals(request.attemptId())
                    && context.purpose() == request.purpose() && context.operation() == Operation.READ_PAGE, ACCESS_UNAVAILABLE);
        } catch (RuntimeException failure) { throw new TraceQueryException(ACCESS_UNAVAILABLE); }
        require(context.availability() != null && context.availability() != Availability.UNKNOWN, EVIDENCE_UNAVAILABLE);
        if (context.availability() == Availability.EXPIRED) throw new TraceQueryException(EVIDENCE_EXPIRED);
        if (context.availability() == Availability.RETAINED_AWAY) throw new TraceQueryException(EVIDENCE_RETAINED_AWAY);

        var position = request.cursor() == null ? null : TraceQueryCursor.decode(request.cursor(), context);
        var head = repository.head(request.attemptId()).orElseThrow(() -> new TraceQueryException(CORRUPT_EVIDENCE));
        require(head.version() == ExecutionTrace.VERSION, UNSUPPORTED_VERSION);
        require(context.scope().equals(head.scope()) && head.sequence() >= 0 && head.sequence() == head.tail(), CORRUPT_EVIDENCE);
        long after = position == null ? 0 : position.after();
        long through = position == null ? head.sequence() : position.through();
        require(through <= head.sequence(), CORRUPT_EVIDENCE);
        if (through == 0) return new Page(TraceQuery.VERSION, List.of(), null);
        var events = repository.page(request.attemptId(), after, through, request.limit());
        require(events != null && events.size() == Math.min(request.limit(), through - after), CORRUPT_EVIDENCE);
        long expected = after;
        for (var event : events) {
            require(event != null && event.sequence() == ++expected && event.recordedAt() != null
                    && event.type() != null && event.actor() != null && event.reason() != null
                    && (event.from() == null) == (event.to() == null), CORRUPT_EVIDENCE);
        }
        String next = expected < through ? TraceQueryCursor.encode(context, new TraceQueryCursor.Position(expected, through)) : null;
        return new Page(TraceQuery.VERSION, events, next);
    }

    private static TraceQueryException sanitized(RuntimeException failure) {
        return new TraceQueryException(failure instanceof TraceQueryException known ? known.reason() : PERSISTENCE_UNAVAILABLE);
    }
}
