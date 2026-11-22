package uk.gegc.quizmaker.features.learning.application.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;
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
import uk.gegc.quizmaker.features.learning.domain.model.LearningReview.Mode;
import uk.gegc.quizmaker.features.learning.domain.repository.*;
import uk.gegc.quizmaker.features.question.application.PublishedQuestionContractService;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final LearningFormRepository formRepository;
    private final Clock clock;
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
            require(command != null && command.source() != null && command.commandId() != null
                    && command.limit() > 0 && command.mode() != null, INVALID_INPUT);
            require(command.mode() == Mode.DUE ? command.questionRevisionId() == null
                    : command.limit() == 1 && command.questionRevisionId() != null, INVALID_INPUT);
            // Session creation already owns D33 admission. Capture and materialization belong to that same command.
            var creation = sessions.create(new LearningSessionService.Create(1, command.commandId(), command.source(),
                    FsrsManifest.approved()));
            var session = creation.session();
            if (creation.replayed()) {
                var saved = repository.start(session.id()).orElseThrow(() -> new LearningSessionException(CONFLICT));
                require(saved.limit() == command.limit() && saved.mode() == command.mode()
                        && Objects.equals(saved.selectedQuestion(), command.questionRevisionId()), CONFLICT);
                return new Observed<>(withProgress(readForm(session.id(), saved), saved.mode(), 0, false), "replayed");
            }
            var reviewItems = select(session.source(), command);
            var questions = reviewItems.stream().map(LearningDueSelection.Item::questionRevisionId).toList();
            var selected = material.describe(session.learnerId(), session.source(), questions);
            boolean shuffled = command.mode() == Mode.DUE && repository.shuffleQuestions(session.source().quizId());
            var items = LearningForm.presentationOrder(selected, shuffled, java.util.concurrent.ThreadLocalRandom.current());
            var view = forms.materialize(new LearningFormService.Materialize(1, session.id(), items)).view();
            // The real schedule writer revalidates the selected identity/version and requires the committed-in-scope form.
            for (var item : reviewItems) {
                schedules.execute(new LearningScheduleService.Capture(1, UUID.randomUUID(), session.source(),
                        item.questionRevisionId(), item.expected(), session.id()));
            }
            repository.recordStart(session.id(), view.form().id(), command.limit(), shuffled,
                    command.mode(), command.questionRevisionId());
            return new Observed<>(withProgress(view, command.mode(), 0, false), items.isEmpty() ? "empty" : "started");
        });
    }

    private List<LearningDueSelection.Item> select(LearningSession.Source source, Start command) {
        if (command.mode() == Mode.DUE) {
            return selection.select(new LearningDueSelectionService.Select(1, source, command.limit(), null)).items();
        }
        var question = command.questionRevisionId();
        var schedule = schedules.read(source, question);
        require(command.mode() != Mode.EARLY
                || schedule.state().dueAt().isAfter(clock.instant().truncatedTo(ChronoUnit.MICROS)), STALE_REVIEW);
        return List.of(new LearningDueSelection.Item(question, schedule.expected(), schedule.state().dueAt()));
    }

    @Override public LearningFormService.View resume(UUID sessionId) {
        return execute("resume", () -> {
            var session = sessions.read(sessionId);
            var start = repository.start(session.id()).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
            var saved = readForm(session.id(), start);
            var view = withProgress(saved, start.mode(), start.reviewedItems(), start.superseded());
            if (view.progress().status() == LearningReview.Status.ACTIVE && !reviewable(session, view.form(), start.reviewedItems())) {
                // The response that completed or superseded this session committed after the progress read above.
                var current = repository.start(session.id()).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
                require(current.formId().equals(saved.form().id()), CORRUPT);
                view = withProgress(saved, current.mode(), current.reviewedItems(), current.superseded());
                require(view.progress().status() != LearningReview.Status.ACTIVE, CORRUPT);
            }
            return new Observed<>(view, "resumed");
        });
    }

    /**
     * An active view offers RESPOND only while the next response can pass the same schedule checks: a middle
     * answer reads its own item, and the final answer releases every capture of the form. Each checked item must
     * be readable under the supported strategy and approved manifest; damaged evidence fails. The final read
     * visits each capture once, as the release does. False means the captures were already released, which only
     * completion or supersession does.
     */
    private boolean reviewable(LearningSession session, LearningForm form, int position) {
        boolean last = position == form.items().size() - 1;
        List<LearningStudyRepository.Capture> captures = last ? repository.captures(session.id(), form.id())
                : repository.capture(session.id(), form.id(), position).map(List::of).orElse(List.of());
        if (captures.isEmpty()) return false;
        require(captures.size() == (last ? form.items().size() : 1), CORRUPT);
        for (var capture : captures) {
            require(capture.position() >= 0 && capture.position() < form.items().size()
                    && capture.questionRevisionId().equals(form.items().get(capture.position()).questionRevisionId()), CORRUPT);
            require("fsrs/v1".equals(capture.strategy()), UNSUPPORTED_VERSION);
            schedules.read(session.source(), capture.questionRevisionId());
        }
        return true;
    }

    @Override public LearningReview.Acknowledgement respond(LearningReview.Command command) {
        return execute("respond", () -> {
            require(command != null && command.sessionId() != null && command.commandId() != null
                    && command.expectedVersion() > 0 && command.sourceItemId() != null && command.rating() != null
                    && command.response() != null && command.response().isObject(), INVALID_INPUT);
            var session = sessions.read(command.sessionId());
            UUID learner = session.learnerId();
            String fingerprint = fingerprint(command);
            budget.chargeIfNew(learner, () -> repository.response(learner, command.commandId()).isPresent());
            var receipt = repository.response(learner, command.commandId());
            if (receipt.isPresent()) {
                require(receipt.get().fingerprint().equals(fingerprint), CONFLICT);
                return new Observed<>(receipt.get().acknowledgement(), "replayed");
            }

            var start = repository.lockStart(session.id()).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
            require(!start.superseded(), REVIEW_SUPERSEDED);
            var form = formRepository.find(session).orElseThrow(() -> new LearningSessionException(CORRUPT));
            require(start.formId().equals(form.id()), CORRUPT);
            int position = start.reviewedItems();
            var current = progress(form, position, false);
            require(!current.completed(), REVIEW_COMPLETE);
            require(command.expectedVersion() == current.version() && command.sourceItemId().equals(current.nextItemId()),
                    STALE_REVIEW);
            var item = form.items().get(position);
            String response = material.validateResponse(session, item, command.response());
            var capture = repository.capture(session.id(), form.id(), position)
                    .orElseThrow(() -> new LearningSessionException(STALE_REVIEW));
            require(capture.questionRevisionId().equals(item.questionRevisionId()), CORRUPT);
            verifyActive(capture);
            Instant nextDue = null;
            if (start.mode() != Mode.PRACTICE) {
                nextDue = rate(session, capture, command, start.mode());
                repository.supersedeOverlaps(session.id(), capture.captured());
            }
            var next = progress(form, position + 1, false);
            var result = new LearningReview.Acknowledgement(session.id(), command.commandId(), item.sourceItemId(), next, nextDue);
            repository.recordResponse(learner, form.id(), command.rating(), fingerprint, response, result);
            if (next.completed()) release(session, form);
            return new Observed<>(result, next.completed() ? "completed" : "accepted");
        });
    }

    private Instant rate(LearningSession session, LearningStudyRepository.Capture capture,
                         LearningReview.Command command, Mode mode) {
        require(capture.activeSequence() == capture.captured().sequence(), STALE_REVIEW);
        try {
            if (mode == Mode.DUE) {
                var snapshot = schedules.read(session.source(), capture.questionRevisionId());
                require(capture.captured().equals(snapshot.expected())
                        && !snapshot.state().dueAt().isAfter(clock.instant().truncatedTo(ChronoUnit.MICROS)), STALE_REVIEW);
            }
            return schedules.execute(new LearningScheduleService.Rate(1, command.commandId(), session.source(),
                    capture.questionRevisionId(), capture.captured(), command.rating())).snapshot().state().dueAt();
        } catch (LearningScheduleException failure) {
            if (failure.reason() == LearningScheduleException.Reason.STALE_STATE) throw new LearningSessionException(STALE_REVIEW);
            throw failure;
        }
    }

    private void release(LearningSession session, LearningForm form) {
        var captures = repository.captures(session.id(), form.id());
        require(captures.size() == form.items().size(), CORRUPT);
        for (int position = 0; position < captures.size(); position++) {
            var capture = captures.get(position);
            require(capture.position() == position
                    && capture.questionRevisionId().equals(form.items().get(position).questionRevisionId()), CORRUPT);
            verifyActive(capture);
            var expected = new LearningSchedule.Expected(capture.captured().cycleId(), capture.captured().cycleVersion(),
                    capture.activeSequence());
            schedules.execute(new LearningScheduleService.Release(1, UUID.randomUUID(), session.source(),
                    capture.questionRevisionId(), expected, session.id()));
        }
    }

    private static void verifyActive(LearningStudyRepository.Capture capture) {
        require("fsrs/v1".equals(capture.strategy()), UNSUPPORTED_VERSION);
        require(capture.activeSequence() != null, STALE_REVIEW);
    }

    private static String fingerprint(LearningReview.Command command) {
        var input = JsonNodeFactory.instance.objectNode().put("sessionId", command.sessionId().toString())
                .put("expectedVersion", command.expectedVersion()).put("sourceItemId", command.sourceItemId().toString())
                .put("rating", command.rating().name()).set("response", command.response());
        try { return CanonicalCommandInput.fingerprint(1, input).digest(); }
        catch (ReceiptException failure) { throw new LearningSessionException(INVALID_INPUT); }
    }

    private static LearningReview.Progress progress(LearningForm form, int reviewed, boolean superseded) {
        require(reviewed >= 0 && reviewed <= form.items().size(), CORRUPT);
        if (superseded) {
            require(reviewed < form.items().size(), CORRUPT);
            return new LearningReview.Progress(reviewed, null, LearningReview.Status.SUPERSEDED);
        }
        UUID next = reviewed == form.items().size() ? null : form.items().get(reviewed).sourceItemId();
        return new LearningReview.Progress(reviewed, next);
    }

    private static LearningFormService.View withProgress(LearningFormService.View view, Mode mode,
                                                         int reviewed, boolean superseded) {
        return new LearningFormService.View(view.form(), view.items(), progress(view.form(), reviewed, superseded), mode);
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
            if (failure.getCause() instanceof DatabaseConflictException conflict) {
                outcome = conflict.outcome();
                throw conflict;
            }
            throw new LearningSessionException(switch (failure.reason()) {
                case UNAVAILABLE -> SOURCE_UNAVAILABLE;
                case UNAUTHENTICATED -> UNAUTHENTICATED;
                case INVALID_INPUT, INVALID_CONTENT, IMAGE_UNAVAILABLE, UNSUPPORTED_VERSION -> UNSUPPORTED_VERSION;
                case STALE_PUBLICATION, COMMAND_CONFLICT -> CONFLICT;
                case TRANSACTION_NOT_ALLOWED -> UNSUPPORTED_TRANSACTION;
                case INVALID_RETAINED_REVISION -> CORRUPT;
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
