package uk.gegc.quizmaker.features.learning.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningHistorySelection.*;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Service
@PreAuthorize("isAuthenticated()")
public class LearningHistorySelectionServiceImpl implements LearningHistorySelectionService {
    private final AuthenticatedUserLookup users;
    private final List<LearningSessionSourceVerifier> access;
    private final List<MistakeHistorySource> mistakes;
    private final List<UnseenContentSource> unseen;
    private final LearningDueSelectionConfig config;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;

    public LearningHistorySelectionServiceImpl(AuthenticatedUserLookup users, List<LearningSessionSourceVerifier> access,
            List<MistakeHistorySource> mistakes, List<UnseenContentSource> unseen, LearningDueSelectionConfig config,
            Clock clock, MeterRegistry metrics, PlatformTransactionManager manager) {
        this.users = users; this.access = List.copyOf(access); this.mistakes = List.copyOf(mistakes);
        this.unseen = List.copyOf(unseen); this.config = config; this.clock = clock; this.metrics = metrics;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(true);
    }

    @Override public LearningHistorySelection select(Select request) {
        require(!TransactionSynchronizationManager.isActualTransactionActive()
                || Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_READ_COMMITTED), UNSUPPORTED_TRANSACTION);
        String outcome = "persistence_unavailable";
        try {
            var result = transaction.execute(status -> {
                try { return selected(request); }
                catch (RuntimeException failure) { throw sanitized(failure); }
            });
            outcome = result.availability() == Availability.COMPLETE ? result.outcome().name().toLowerCase(Locale.ROOT)
                    : result.availability().name().toLowerCase(Locale.ROOT);
            return result;
        } catch (RuntimeException failure) {
            throw sanitized(failure);
        } finally {
            try { metrics.counter("quizmaker.learning.history.selection", "version", "1", "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* Optional telemetry cannot change the read outcome. */ }
        }
    }

    private LearningHistorySelection selected(Select request) {
        require(request != null && request.source() != null && request.mode() != null, INVALID_INPUT);
        require(request.version() == 1, UNSUPPORTED_VERSION);
        Integer maximum = config.getMaxBatchSize();
        require(maximum != null && maximum > 0 && maximum < Integer.MAX_VALUE, SOURCE_UNAVAILABLE);
        require(request.limit() > 0 && request.limit() <= maximum, INVALID_INPUT);
        var after = request.after();
        if (after != null) {
            require(after.version() == 1, UNSUPPORTED_VERSION);
            require(request.source().equals(after.source()) && after.mode() == request.mode()
                    && after.authoredPosition() >= 0, INVALID_INPUT);
        }
        var learner = LearningActor.lock(users);
        require(access.size() == 1 && access.get(0).allows(learner, request.source()), SOURCE_UNAVAILABLE);
        int position = after == null ? -1 : after.authoredPosition();
        Batch batch;
        if (request.mode() == Mode.INCORRECT_ONLY) {
            batch = mistakes.size() == 1 ? mistakes.get(0).mistakes(learner, request.source(), position, request.limit() + 1)
                    : new Batch(Availability.SOURCE_UNAVAILABLE, List.of());
        } else {
            batch = unseen.size() == 1 ? unseen.get(0).unseen(learner, request.source(), position, request.limit() + 1)
                    : new Batch(Availability.SOURCE_UNAVAILABLE, List.of());
        }
        require(batch != null && batch.items().size() <= request.limit() + 1, CORRUPT);
        var identities = new HashSet<UUID>();
        for (var item : batch.items()) {
            require(item.authoredPosition() > position && identities.add(item.questionRevisionId())
                    && (item.evidence() != null) == (request.mode() == Mode.INCORRECT_ONLY), CORRUPT);
            position = item.authoredPosition();
        }
        boolean more = batch.items().size() > request.limit();
        var items = List.copyOf(batch.items().subList(0, Math.min(batch.items().size(), request.limit())));
        var next = more ? new Cursor(1, request.source(), request.mode(), items.get(items.size() - 1).authoredPosition()) : null;
        var outcome = items.isEmpty() ? Outcome.EMPTY : items.size() < request.limit() ? Outcome.PARTIAL : Outcome.READY;
        return new LearningHistorySelection(1, request.source(), request.mode(), clock.instant().truncatedTo(ChronoUnit.MICROS),
                request.limit(), batch.availability(), outcome, items, next);
    }

    private static RuntimeException sanitized(RuntimeException failure) {
        if (failure instanceof LearningSessionException known) return new LearningSessionException(known.reason());
        var conflict = DatabaseConflictException.from(failure);
        return conflict.isPresent() ? conflict.get() : new LearningSessionException(PERSISTENCE_UNAVAILABLE);
    }
}
