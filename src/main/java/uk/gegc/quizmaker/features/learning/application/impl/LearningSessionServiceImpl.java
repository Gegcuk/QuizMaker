package uk.gegc.quizmaker.features.learning.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningSessionRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Service
@PreAuthorize("isAuthenticated()")
public class LearningSessionServiceImpl implements LearningSessionService {
    private final LearningSessionRepository repository;
    private final AuthenticatedUserLookup users;
    private final List<LearningSessionSourceVerifier> sources;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;

    public LearningSessionServiceImpl(LearningSessionRepository repository, AuthenticatedUserLookup users,
            List<LearningSessionSourceVerifier> sources, Clock clock, MeterRegistry metrics,
            PlatformTransactionManager manager) {
        this.repository = repository;
        this.users = users;
        this.sources = List.copyOf(sources);
        this.clock = clock;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public Result create(Create command) {
        return run("create", () -> {
            require(command != null, INVALID_INPUT);
            require(command.version() == 1, UNSUPPORTED_VERSION);
            require(command.commandId() != null && command.source() != null && command.configuration() != null,
                    INVALID_INPUT);
            UUID learner = actor();
            verify(learner, command.source());
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var candidate = new LearningSession(UUID.randomUUID(), 1, learner, command.commandId(),
                    command.source(), command.configuration(), LearningSession.Status.CREATED, 1, now, now);
            var saved = repository.insertOrRead(candidate);
            require(saved.learnerId().equals(learner) && saved.createCommandId().equals(command.commandId()), CORRUPT);
            require(saved.source().equals(command.source()) && saved.configuration().equals(command.configuration()), CONFLICT);
            boolean replay = !saved.id().equals(candidate.id());
            if (replay) require(repository.hasCreationReceipt(saved), CORRUPT);
            else repository.recordCreation(saved);
            return new Result(saved, replay);
        });
    }

    @Override public LearningSession read(UUID sessionId) {
        return run("read", () -> {
            require(sessionId != null, INVALID_INPUT);
            UUID learner = actor();
            var session = repository.find(learner, sessionId)
                    .orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
            require(session.learnerId().equals(learner) && session.id().equals(sessionId), CORRUPT);
            verify(learner, session.source());
            require(repository.hasCreationReceipt(session), CORRUPT);
            return session;
        });
    }

    private UUID actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        require(authentication instanceof UsernamePasswordAuthenticationToken && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserDetails, UNAUTHENTICATED);
        var principal = (UserDetails) authentication.getPrincipal();
        String name = principal.getUsername();
        require(name != null && !name.isBlank() && name.length() <= 50 && name.equals(authentication.getName())
                && principal.isEnabled() && principal.isAccountNonLocked() && principal.isAccountNonExpired()
                && principal.isCredentialsNonExpired(), UNAUTHENTICATED);
        return users.lockActiveUserId(name).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
    }

    private void verify(UUID learner, LearningSession.Source source) {
        require(sources.size() == 1 && sources.get(0).allows(learner, source), SOURCE_UNAVAILABLE);
        require(repository.sourceExists(source), SOURCE_UNAVAILABLE);
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
                catch (LearningSessionException failure) { throw new LearningSessionException(failure.reason()); }
                catch (RuntimeException failure) {
                    throw DatabaseConflictException.from(failure).<RuntimeException>map(value -> value)
                            .orElseGet(() -> new LearningSessionException(PERSISTENCE_UNAVAILABLE));
                }
            });
            outcome = result instanceof Result r ? (r.replayed() ? "replayed" : "created") : "read";
            return result;
        } catch (LearningSessionException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (RuntimeException failure) {
            var conflict = DatabaseConflictException.from(failure);
            if (conflict.isPresent()) { outcome = conflict.get().outcome(); throw conflict.get(); }
            throw new LearningSessionException(PERSISTENCE_UNAVAILABLE);
        } finally {
            try { metrics.counter("quizmaker.learning.session", "operation", operation, "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* Telemetry cannot change a committed result or original failure. */ }
        }
    }
}
