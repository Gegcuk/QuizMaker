package uk.gegc.quizmaker.features.learning.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;
import uk.gegc.quizmaker.features.learning.infra.scheduling.FsrsStateFormat;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.require;

@Service
@PreAuthorize("isAuthenticated()")
public class LearningScheduleServiceImpl implements LearningScheduleService {
    private final LearningScheduleRepository repository;
    private final AuthenticatedUserLookup users;
    private final List<LearningSessionSourceVerifier> sources;
    private final List<LearningSessionCompletionVerifier> completions;
    private final LearningSessionService sessions;
    private final LearningSchedulingStrategy strategy;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;
    private final FsrsStateFormat format = new FsrsStateFormat();

    public LearningScheduleServiceImpl(LearningScheduleRepository repository, AuthenticatedUserLookup users,
            List<LearningSessionSourceVerifier> sources, List<LearningSessionCompletionVerifier> completions,
            LearningSessionService sessions, LearningSchedulingStrategy strategy, Clock clock,
            MeterRegistry metrics, PlatformTransactionManager manager) {
        this.repository = repository; this.users = users; this.sources = List.copyOf(sources);
        this.completions = List.copyOf(completions); this.sessions = sessions; this.strategy = strategy;
        this.clock = clock; this.metrics = metrics;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public Result execute(Command command) {
        return run(operation(command), () -> {
            validate(command);
            var key = authorize(command.source(), command.questionRevisionId());
            String input = input(command);
            UUID reservation = UUID.randomUUID();
            var receipt = repository.reserve(key, command.id(), reservation, input);
            require(receipt.input().equals(input), CONFLICT);
            if (!reservation.equals(receipt.reservationId())) {
                require(receipt.result() != null, CORRUPT);
                verifySnapshot(key, receipt.result());
                return new Result(receipt.result(), true);
            }
            require(receipt.result() == null, CORRUPT);
            var current = repository.lock(key);
            Snapshot next;
            if (command instanceof Enroll enrollment) {
                next = current.orElseGet(() -> initial(key, enrollment.manifest(), 1));
                require(next.state().manifest().equals(enrollment.manifest()), CONFLICT);
                if (current.isEmpty()) repository.createCycle(key, next, 0, command.id());
            } else {
                var previous = current.orElseThrow(() -> new LearningScheduleException(UNAVAILABLE));
                verifySnapshot(key, previous);
                Expected expected = expected(command);
                require(previous.expected().equals(expected), STALE_STATE);
                next = previous;
                if (command instanceof Rate rate) {
                    var review = new LearningSchedule.Review(rate.id(), previous.state().identity(),
                            expected.sequence(), rate.rating(), now());
                    var transition = strategy.review(previous.state(), review);
                    next = new Snapshot(previous.cycleVersion(), transition.state());
                    repository.advance(key, previous, transition);
                } else if (command instanceof Restart restart) {
                    require(!repository.hasCaptures(key), ACTIVE_SESSION);
                    require(previous.cycleVersion() < Long.MAX_VALUE, INVALID_STATE);
                    next = initial(key, restart.manifest(), previous.cycleVersion() + 1);
                    repository.createCycle(key, next, previous.cycleVersion(), command.id());
                } else {
                    UUID sessionId = command instanceof Capture capture ? capture.sessionId() : ((Release) command).sessionId();
                    LearningSession session;
                    try { session = sessions.read(sessionId); }
                    catch (LearningSessionException failure) { throw new LearningScheduleException(SESSION_UNAVAILABLE); }
                    require(session.learnerId().equals(key.learnerId()) && session.source().equals(key.source())
                            && session.configuration().equals(previous.state().manifest())
                            && repository.sessionContains(key, sessionId), SESSION_UNAVAILABLE);
                    if (command instanceof Capture) repository.capture(key, previous, sessionId);
                    else {
                        require(completions.size() == 1 && completions.get(0).completed(session), SESSION_INCOMPLETE);
                        repository.release(key, previous, sessionId);
                    }
                }
            }
            verifySnapshot(key, next);
            repository.complete(key, command.id(), reservation, next);
            return new Result(next, false);
        });
    }

    @Override public Snapshot read(LearningSession.Source source, UUID questionRevisionId) {
        return run("read", () -> {
            var key = authorize(source, questionRevisionId);
            var saved = repository.find(key).orElseThrow(() -> new LearningScheduleException(UNAVAILABLE));
            verifySnapshot(key, saved);
            return saved;
        });
    }

    private Key authorize(LearningSession.Source source, UUID question) {
        require(source != null && question != null, INVALID_INPUT);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        require(authentication instanceof UsernamePasswordAuthenticationToken && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserDetails, UNAUTHENTICATED);
        var principal = (UserDetails) authentication.getPrincipal();
        String name = principal.getUsername();
        require(name != null && !name.isBlank() && name.length() <= 50 && name.equals(authentication.getName())
                && principal.isEnabled() && principal.isAccountNonLocked() && principal.isAccountNonExpired()
                && principal.isCredentialsNonExpired(), UNAUTHENTICATED);
        UUID learner = users.lockActiveUserId(name).orElseThrow(() -> new LearningScheduleException(UNAVAILABLE));
        require(sources.size() == 1 && sources.get(0).allows(learner, source), SOURCE_UNAVAILABLE);
        var key = new Key(learner, source, question);
        require(repository.sourceExists(key), SOURCE_UNAVAILABLE);
        return key;
    }

    private Snapshot initial(Key key, FsrsManifest manifest, long version) {
        var identity = new LearningSchedule.Identity(key.learnerId(), key.source().quizId(),
                key.source().quizRevisionId(), key.questionRevisionId(), UUID.randomUUID());
        return new Snapshot(version, strategy.initial(identity, manifest, now()));
    }

    private Instant now() {
        var value = clock.instant().truncatedTo(ChronoUnit.MICROS);
        require(!value.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && value.isBefore(Instant.parse("+10000-01-01T00:00:00Z")), INVALID_TIME);
        return value;
    }

    private static void validate(Command c) {
        require(c != null, INVALID_INPUT);
        require(c.version() == 1, UNSUPPORTED_VERSION);
        require(c.id() != null && c.source() != null && c.questionRevisionId() != null, INVALID_INPUT);
        if (c instanceof Enroll e) require(e.manifest() != null, INVALID_INPUT);
        else {
            var e = expected(c);
            require(e != null && e.cycleId() != null && e.cycleVersion() > 0 && e.sequence() >= 0, INVALID_INPUT);
            if (c instanceof Rate r) require(r.rating() != null, INVALID_INPUT);
            if (c instanceof Restart r) require(r.manifest() != null, INVALID_INPUT);
            if (c instanceof Capture r) require(r.sessionId() != null, INVALID_INPUT);
            if (c instanceof Release r) require(r.sessionId() != null, INVALID_INPUT);
        }
    }

    private static Expected expected(Command c) {
        if (c instanceof Rate r) return r.expected();
        if (c instanceof Restart r) return r.expected();
        if (c instanceof Capture r) return r.expected();
        return ((Release) c).expected();
    }

    /** Bounded, canonical metadata only. Never hashes/reads question or document content. */
    private String input(Command c) {
        var s = c.source();
        String base = String.join("|", "1", operation(c), s.version() + "", s.quizId().toString(),
                s.quizRevisionId().toString(), s.revisionSchemaVersion() + "", c.questionRevisionId().toString());
        if (c instanceof Enroll e) return base + "|" + format.encodeManifest(e.manifest());
        var e = expected(c);
        base += "|" + e.cycleId() + "|" + e.cycleVersion() + "|" + e.sequence();
        if (c instanceof Rate r) return base + "|" + r.rating();
        if (c instanceof Restart r) return base + "|" + format.encodeManifest(r.manifest());
        return base + "|" + (c instanceof Capture r ? r.sessionId() : ((Release) c).sessionId());
    }

    private static String operation(Command c) {
        if (c instanceof Enroll) return "enroll";
        if (c instanceof Rate) return "rate";
        if (c instanceof Restart) return "restart";
        if (c instanceof Capture) return "capture";
        return c instanceof Release ? "release" : "invalid";
    }

    private static void verifySnapshot(Key k, Snapshot s) {
        require(s != null && s.cycleVersion() > 0 && s.state() != null, CORRUPT);
        var id = s.state().identity();
        require(id.learnerId().equals(k.learnerId()) && id.quizId().equals(k.source().quizId())
                && id.quizRevisionId().equals(k.source().quizRevisionId())
                && id.questionRevisionId().equals(k.questionRevisionId()), CORRUPT);
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
                catch (LearningScheduleException failure) { throw new LearningScheduleException(failure.reason()); }
                catch (RuntimeException failure) {
                    throw DatabaseConflictException.from(failure).<RuntimeException>map(value -> value)
                            .orElseGet(() -> new LearningScheduleException(PERSISTENCE_UNAVAILABLE));
                }
            });
            outcome = result instanceof Result r && r.replayed() ? "replayed" : "success";
            return result;
        } catch (LearningScheduleException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT); throw failure;
        } catch (RuntimeException failure) {
            var conflict = DatabaseConflictException.from(failure);
            if (conflict.isPresent()) { outcome = conflict.get().outcome(); throw conflict.get(); }
            throw new LearningScheduleException(PERSISTENCE_UNAVAILABLE);
        } finally {
            try { metrics.counter("quizmaker.learning.schedule", "operation", operation, "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* Telemetry cannot change the transaction result. */ }
        }
    }
}
