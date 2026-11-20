package uk.gegc.quizmaker.features.learning.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningFormRepository;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.require;

@Service
@PreAuthorize("isAuthenticated()")
public class LearningFormServiceImpl implements LearningFormService {
    private final LearningSessionService sessions;
    private final LearningFormRepository repository;
    private final List<LearningFormContent> content;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;

    public LearningFormServiceImpl(LearningSessionService sessions, LearningFormRepository repository,
            List<LearningFormContent> content, Clock clock, MeterRegistry metrics, PlatformTransactionManager manager) {
        this.sessions = sessions; this.repository = repository; this.content = List.copyOf(content);
        this.clock = clock; this.metrics = metrics;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public Result materialize(Materialize command) {
        return run("materialize", () -> {
            require(command != null && command.sessionId() != null, INVALID_INPUT);
            require(command.version() == 1, UNSUPPORTED_VERSION);
            var items = LearningForm.selection(command.items());
            var session = sessions.read(command.sessionId());
            repository.lockSession(session); // Same-session creators serialize without missing-row gap locks.
            var existing = repository.find(session);
            if (existing.isPresent()) {
                require(existing.get().items().equals(items), CONFLICT);
                return new Result(present(session, existing.get()), true);
            }
            var form = LearningForm.create(UUID.randomUUID(), session, items,
                    clock.instant().truncatedTo(ChronoUnit.MICROS));
            var view = present(session, form); // No authoritative write until safe material is available.
            repository.insert(form);
            return new Result(view, false);
        });
    }

    @Override public View read(UUID sessionId) {
        return run("read", () -> {
            require(sessionId != null, INVALID_INPUT);
            var session = sessions.read(sessionId);
            var form = repository.find(session).orElseThrow(() -> new LearningFormException(UNAVAILABLE));
            return present(session, form);
        });
    }

    private View present(LearningSession session, LearningForm form) {
        require(form.sessionId().equals(session.id()) && form.source().equals(session.source()), CORRUPT);
        repository.verifyItems(session, form.items());
        require(content.size() == 1, SOURCE_UNAVAILABLE);
        List<LearningFormContent.PresentedItem> shown;
        try { shown = content.get(0).read(session, form.items()); }
        catch (RuntimeException failure) {
            throw DatabaseConflictException.from(failure).<RuntimeException>map(value -> value)
                    .orElseGet(() -> new LearningFormException(SOURCE_UNAVAILABLE));
        }
        require(shown != null && shown.size() == form.items().size(), SOURCE_UNAVAILABLE);
        for (int i = 0; i < shown.size(); i++) {
            var item = shown.get(i);
            require(item != null && form.items().get(i).equals(item.item()) && item.questionText() != null
                    && !item.questionText().isBlank() && item.questionText().codePointCount(0, item.questionText().length()) <= 1000
                    && item.content() != null, SOURCE_UNAVAILABLE);
            require(item.item().optionIds().equals(LearningFormContent.optionIds(item.content())), SOURCE_UNAVAILABLE);
        }
        return new View(form, shown);
    }

    private <T> T run(String operation, Supplier<T> work) {
        require(!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_READ_COMMITTED), UNSUPPORTED_TRANSACTION);
        String outcome = "persistence_unavailable";
        try {
            T result = transaction.execute(status -> {
                try { return work.get(); }
                catch (LearningFormException failure) { throw new LearningFormException(failure.reason()); }
                catch (LearningSessionException failure) { throw new LearningSessionException(failure.reason()); }
                catch (RuntimeException failure) { throw safe(failure); }
            });
            outcome = result instanceof Result r ? (r.replayed() ? "replayed" : r.view().form().outcome().name().toLowerCase(Locale.ROOT)) : "read";
            return result;
        } catch (LearningFormException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT); throw failure;
        } catch (LearningSessionException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT); throw failure;
        } catch (RuntimeException failure) {
            var safe = safe(failure);
            if (safe instanceof DatabaseConflictException conflict) outcome = conflict.outcome();
            throw safe;
        } finally {
            try { metrics.counter("quizmaker.learning.form", "version", "1", "source", "quiz_revision",
                    "operation", operation, "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* Telemetry cannot change a committed result. */ }
        }
    }
    private static RuntimeException safe(RuntimeException failure) {
        return DatabaseConflictException.from(failure).<RuntimeException>map(value -> value)
                .orElseGet(() -> new LearningFormException(PERSISTENCE_UNAVAILABLE));
    }
}
