package uk.gegc.quizmaker.features.quiz.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.media.application.RetainedImageService;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizPublicationRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuestionSourceScopeRepository;
import uk.gegc.quizmaker.features.quiz.infra.publication.PublicationContent;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.Reason.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.require;

@Service
@Slf4j
public class QuizRevisionPublicationServiceImpl implements QuizRevisionPublicationService {
    private final QuizPublicationRepository repository;
    private final QuizRevisionRepository revisions;
    private final QuestionSourceScopeRepository questionSources;
    private final RetainedImageService images;
    private final PublicationContent content;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;
    private final TransactionTemplate publicationTransaction;

    public QuizRevisionPublicationServiceImpl(QuizPublicationRepository repository, QuizRevisionRepository revisions,
                                             RetainedImageService images, PublicationContent content, Clock clock,
                                             MeterRegistry metrics, PlatformTransactionManager manager,
                                             QuestionSourceScopeRepository questionSources) {
        this.repository = repository; this.revisions = revisions; this.images = images;
        this.content = content; this.clock = clock; this.metrics = metrics;
        this.questionSources = questionSources;
        transaction = new TransactionTemplate(manager);
        publicationTransaction = new TransactionTemplate(manager);
        // Receipt reads must not pin an old snapshot before the current question rows are locked.
        publicationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public Result publish(Command command) {
        require(command != null, INVALID_INPUT);
        require(!TransactionSynchronizationManager.isActualTransactionActive(), TRANSACTION_NOT_ALLOWED);
        return run("publish", () -> {
            String principal = principal();
            var actor = repository.actor(principal).orElseThrow(() -> new PublicationException(UNAVAILABLE));
            var draft = authorized(actor, command.quizId(), true);
            String requestHash = PublicationContent.requestHash(command);
            var receipt = repository.receipt(draft.id(), actor.id(), command.commandId());
            if (receipt.isPresent()) {
                require(receipt.get().requestHash().equals(requestHash), COMMAND_CONFLICT);
                // Replay returns the old acknowledgement without rereading an edited draft or moving its pointer back.
                return new Result(receipt.get().publication(), true);
            }
            var questions = repository.lockQuestions(draft.id());
            var byId = questions.stream().collect(Collectors.toMap(Question::id, q -> q));
            require(byId.size() == questions.size() && byId.keySet().equals(new HashSet<>(command.questionOrder())), INVALID_CONTENT);
            var sources = questionSources.lockSources(command.questionOrder());
            require(sources.stream().map(QuestionSourceScopeRepository.SourceScope::questionId).collect(Collectors.toSet()).equals(byId.keySet())
                    && (actor.elevated() || actor.permissions().contains("QUESTION_ADMIN")
                    || sources.stream().allMatch(source -> source.ownedBy(actor.id()))), UNAVAILABLE);
            var ordered = command.questionOrder().stream().map(byId::get).toList();
            var requested = content.references(ordered);
            Map<UUID, Map<UUID, RetainedImageService.ImageReference>> retained;
            try {
                retained = images.acquireForPublication(requested, () -> authorized(
                        repository.actor(principal).orElseThrow(() -> new PublicationException(UNAVAILABLE)), draft.id(), true));
            } catch (PublicationException denied) { throw denied; }
            catch (RuntimeException unavailable) { throw new PublicationException(IMAGE_UNAVAILABLE); }
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var materialized = content.materialize(draft, ordered, retained, now);
            var current = repository.current(draft.id());
            boolean identical = current.map(c -> c.revision().contentHash().equals(materialized.hash())).orElse(false);
            require(command.expectedVersion() <= current.map(Current::version).orElse(0L), STALE_PUBLICATION);
            require(identical || current.map(Current::version).orElse(0L) == command.expectedVersion(), STALE_PUBLICATION);
            Current result;
            if (identical) result = current.orElseThrow();
            else {
                var revision = repository.findContent(draft.id(), materialized.hash()).orElseGet(() -> {
                    var added = new Revision(materialized.revision().id(), draft.id(), actor.id(), 1, materialized.hash(), now);
                    revisions.insert(materialized.revision());
                    repository.insert(added, materialized.images());
                    return added;
                });
                result = new Current(Math.addExact(current.map(Current::version).orElse(0L), 1L), revision);
                repository.pointTo(draft.id(), result);
            }
            repository.record(command, actor.id(), requestHash, result, now);
            return new Result(result, false);
        });
    }

    @Override public Optional<Current> current(UUID quizId) {
        return run("current", () -> { authorizeRead(quizId); return repository.current(quizId); });
    }
    @Override public Page<Revision> history(UUID quizId, int page, int size) {
        require(page >= 0 && size > 0 && size <= 100, INVALID_INPUT);
        return run("history", () -> { authorizeRead(quizId); return repository.history(quizId, page, size); });
    }
    @Override public PublishedContent read(UUID quizId, UUID revisionId) {
        require(revisionId != null, INVALID_INPUT);
        return run("read", () -> {
            var draft = authorizeRead(quizId);
            var revision = repository.revision(quizId, revisionId).orElseThrow(() -> new PublicationException(UNAVAILABLE));
            var saved = revisions.findById(draft.ownerId(), revisionId).orElseThrow(() -> new PublicationException(UNAVAILABLE));
            return new PublishedContent(revision, saved, repository.images(revisionId));
        });
    }
    private Draft authorizeRead(UUID quizId) {
        var actor = repository.actor(principal()).orElseThrow(() -> new PublicationException(UNAVAILABLE));
        return authorized(actor, quizId, false);
    }
    private Draft authorized(Actor actor, UUID quizId, boolean publishing) {
        require(quizId != null, INVALID_INPUT);
        require(actor.elevated() || actor.permissions().contains("QUIZ_UPDATE"), UNAVAILABLE);
        var draft = repository.draft(quizId, publishing).orElseThrow(() -> new PublicationException(UNAVAILABLE));
        require(actor.elevated() || actor.id().equals(draft.ownerId()), UNAVAILABLE);
        require(!publishing || !"PUBLIC".equals(draft.visibility()) || actor.elevated(), UNAVAILABLE);
        return draft;
    }
    private String principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        require(authentication != null && authentication.isAuthenticated()
                && !new AuthenticationTrustResolverImpl().isAnonymous(authentication), UNAUTHENTICATED);
        return authentication.getName();
    }

    private <T> T run(String operation, Supplier<T> action) {
        var timer = Timer.start(); String outcome = "unknown_outcome";
        class Failure { PublicationException known; }
        var failure = new Failure();
        try {
            T result = (operation.equals("publish") ? publicationTransaction : transaction).execute(status -> {
                try { return action.get(); }
                catch (RuntimeException exception) {
                    failure.known = exception instanceof PublicationException p ? p
                            : new PublicationException(exception instanceof uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException
                                    ? INVALID_CONTENT : PERSISTENCE_UNAVAILABLE);
                    throw failure.known;
                }
            });
            outcome = result instanceof Result r ? (r.replayed() ? "replayed" : "committed") : "read";
            return result;
        } catch (RuntimeException exception) {
            var reason = exception == failure.known ? failure.known.reason() : UNKNOWN_OUTCOME;
            outcome = reason.name().toLowerCase(Locale.ROOT);
            if (reason == PERSISTENCE_UNAVAILABLE || reason == UNKNOWN_OUTCOME)
                log.warn("Quiz publication operation failed: operation={}, version=1, outcome={}", operation, outcome);
            throw new PublicationException(reason);
        } finally {
            try { timer.stop(Timer.builder("quiz.publication").tags("operation", operation, "version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* Telemetry cannot change a committed publication. */ }
        }
    }
}
