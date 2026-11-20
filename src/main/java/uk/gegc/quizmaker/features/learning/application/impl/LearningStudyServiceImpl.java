package uk.gegc.quizmaker.features.learning.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.repository.*;
import uk.gegc.quizmaker.features.question.application.PublishedQuestionContractService;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import java.util.*;
import java.util.function.Supplier;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Transactional(isolation = Isolation.READ_COMMITTED)
@ConditionalOnProperty(name = "quizmaker.learning.sessions-enabled", havingValue = "true")
public class LearningStudyServiceImpl implements LearningStudyService {
    private final AuthenticatedUserLookup users;
    private final List<LearningSessionSourceVerifier> authorities;
    private final LearningCommandBudget budget;
    private final LearningStudyRepository repository;
    private final PublishedQuestionContractService bindings;
    private final LearningScheduleService schedules;
    private final LearningDueSelectionService selection;
    private final LearningDueSelectionConfig limits;
    private final LearningItemMaterial material;
    private final LearningSessionService sessions;
    private final LearningFormService forms;
    private final MeterRegistry metrics;

    @Override public Enrollment enroll(Enroll command) {
        return execute("enroll", () -> {
            require(command != null && command.source() != null && command.commandId() != null
                    && command.questions() != null && !command.questions().isEmpty(), INVALID_INPUT);
            var source = command.source();
            UUID learner = LearningActor.lock(users);
            require(authorities.size() == 1 && authorities.get(0).allows(learner, source), SOURCE_UNAVAILABLE);
            UUID key = command.commandId();
            budget.chargeIfNew(learner, () -> repository.enrollment(learner, key).isPresent());
            var saved = repository.enrollment(learner, key);
            if (saved.isPresent()) {
                require(saved.get().revisionId().equals(source.quizRevisionId()), CONFLICT);
                var items = repository.enrolledItems(learner, key);
                require(saved.get().count() == items.size(), CORRUPT);
                require(command.questions().equals(items.stream().map(EnrolledItem::questionRevisionId).toList()), CONFLICT);
                return new Observed<>(new Enrollment(source, items), "replayed");
            }
            Integer max = limits.getMaxBatchSize();
            require(max != null && max > 0 && max < Integer.MAX_VALUE, PERSISTENCE_UNAVAILABLE);
            require(command.questions().size() <= max, INVALID_INPUT);
            var questions = new ArrayList<>(command.questions());
            require(!questions.contains(null) && new HashSet<>(questions).size() == questions.size(), INVALID_INPUT);
            if (!repository.prepared(source.quizRevisionId())) {
                bindings.bind(source.quizId(), source.quizRevisionId());
                repository.recordPreparation(source.quizRevisionId());
            }
            material.describe(learner, source, questions);
            var enrolled = new ArrayList<EnrolledItem>(questions.size());
            for (var question : questions) {
                var current = schedules.execute(new LearningScheduleService.Enroll(1, UUID.randomUUID(),
                        source, question, FsrsManifest.approved())).snapshot().expected();
                enrolled.add(new EnrolledItem(question, current.cycleId(), current.cycleVersion()));
            }
            repository.recordEnrollment(learner, key, source.quizRevisionId(), enrolled);
            return new Observed<>(new Enrollment(source, enrolled), "enrolled");
        });
    }

    @Override public LearningFormService.View start(Start command) {
        return execute("start", () -> {
            require(command != null && command.source() != null && command.commandId() != null && command.limit() > 0,
                    INVALID_INPUT);
            // Session creation already owns D33 admission. Capture and materialization belong to that same command.
            var creation = sessions.create(new LearningSessionService.Create(1, command.commandId(), command.source(),
                    FsrsManifest.approved()));
            var session = creation.session();
            if (creation.replayed()) {
                var saved = repository.start(session.id()).orElseThrow(() -> new LearningSessionException(CONFLICT));
                require(saved.limit() == command.limit(), CONFLICT);
                return new Observed<>(readForm(session.id(), saved), "replayed");
            }
            var due = selection.select(new LearningDueSelectionService.Select(1, session.source(), command.limit(), null));
            var questions = due.items().stream().map(LearningDueSelection.Item::questionRevisionId).toList();
            var selected = material.describe(session.learnerId(), session.source(), questions);
            boolean shuffled = repository.shuffleQuestions(session.source().quizId());
            var items = LearningForm.presentationOrder(selected, shuffled, java.util.concurrent.ThreadLocalRandom.current());
            var view = forms.materialize(new LearningFormService.Materialize(1, session.id(), items)).view();
            // The real schedule writer revalidates the selected identity/version and requires the committed-in-scope form.
            for (var item : due.items()) {
                schedules.execute(new LearningScheduleService.Capture(1, UUID.randomUUID(), session.source(),
                        item.questionRevisionId(), item.expected(), session.id()));
            }
            repository.recordStart(session.id(), view.form().id(), command.limit(), shuffled);
            return new Observed<>(view, items.isEmpty() ? "empty" : "started");
        });
    }

    @Override public LearningFormService.View resume(UUID sessionId) {
        return execute("resume", () -> {
            var session = sessions.read(sessionId);
            var start = repository.start(session.id()).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
            return new Observed<>(readForm(session.id(), start), "resumed");
        });
    }

    private LearningFormService.View readForm(UUID sessionId, LearningStudyRepository.Start start) {
        var view = forms.read(sessionId);
        require(start.formId().equals(view.form().id()), CORRUPT);
        return view;
    }

    private record Observed<T>(T value, String outcome) { }

    private <T> T execute(String operation, Supplier<Observed<T>> work) {
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_READ_COMMITTED), UNSUPPORTED_TRANSACTION);
        long started = System.nanoTime();
        String outcome = "failed";
        boolean deferred = false;
        try {
            var result = work.get();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    String outcome = switch (status) {
                        case STATUS_COMMITTED -> result.outcome();
                        case STATUS_ROLLED_BACK -> "rolled_back";
                        default -> "unknown";
                    };
                    record(operation, outcome, started);
                }
            });
            deferred = true;
            return result.value();
        } catch (LearningSessionException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (LearningScheduleException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (LearningFormException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (RateLimitExceededException failure) {
            outcome = "rate_limited";
            throw failure;
        } catch (DataAccessException failure) {
            throw DatabaseConflictException.from(failure).<RuntimeException>map(conflict -> conflict)
                    .orElseGet(() -> new LearningSessionException(PERSISTENCE_UNAVAILABLE));
        } catch (PublicationException failure) {
            throw new LearningSessionException(switch (failure.reason()) {
                case UNAVAILABLE -> SOURCE_UNAVAILABLE;
                case UNAUTHENTICATED -> UNAUTHENTICATED;
                case INVALID_INPUT, INVALID_CONTENT, IMAGE_UNAVAILABLE, UNSUPPORTED_VERSION -> UNSUPPORTED_VERSION;
                case STALE_PUBLICATION, COMMAND_CONFLICT -> CONFLICT;
                case TRANSACTION_NOT_ALLOWED -> UNSUPPORTED_TRANSACTION;
                case PERSISTENCE_UNAVAILABLE, UNKNOWN_OUTCOME -> PERSISTENCE_UNAVAILABLE;
            });
        } catch (ContractResolutionException failure) {
            throw new LearningSessionException(switch (failure.reason()) {
                case PERSISTENCE_UNAVAILABLE -> PERSISTENCE_UNAVAILABLE;
                case BINDING_CONFLICT -> CONFLICT;
                case INVALID_STORED_BINDING, TRANSACTION_REQUIRED, INVALID_SCOPE, SCOPE_REQUIRED, SCOPE_VIOLATION -> CORRUPT;
                case REVISION_UNAVAILABLE -> SOURCE_UNAVAILABLE;
                default -> UNSUPPORTED_VERSION;
            });
        } finally {
            if (!deferred) record(operation, outcome, started);
        }
    }

    private void record(String operation, String outcome, long started) {
        try {
            Timer.builder("quizmaker.learning.study").tags("operation", operation, "outcome", outcome,
                    "strategy", "fsrs", "version", "1").register(metrics)
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) { /* Telemetry must not change transaction semantics. */ }
    }
}
