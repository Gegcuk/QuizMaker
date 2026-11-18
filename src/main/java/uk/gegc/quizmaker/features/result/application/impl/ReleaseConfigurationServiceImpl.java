package uk.gegc.quizmaker.features.result.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactionException;
import uk.gegc.quizmaker.features.result.application.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.model.release.*;
import uk.gegc.quizmaker.features.result.domain.repository.ReleaseConfigurationRepository;
import uk.gegc.quizmaker.features.result.infra.release.ReleaseConfigurationFormat;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.*;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.Reason.*;

@Service
@Slf4j
public final class ReleaseConfigurationServiceImpl implements ReleaseConfigurationService {
    private final ReleaseConfigurationSource source;
    private final ReleaseConfigurationRepository repository;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate write, read;
    private final ReleaseConfigurationFormat format = new ReleaseConfigurationFormat();
    public ReleaseConfigurationServiceImpl(ReleaseConfigurationSource source, ReleaseConfigurationRepository repository,
            Clock clock, MeterRegistry metrics, PlatformTransactionManager manager) {
        this.source = source; this.repository = repository; this.clock = clock; this.metrics = metrics;
        write = AttemptTransactions.readCommitted(manager);
        read = AttemptTransactions.readCommitted(manager); read.setReadOnly(true);
    }
    @Override public ReleaseConfiguration preview(UUID quiz, UUID revision, ReleaseConfiguration rule) {
        return run("preview", read, () -> {
            authorize(quiz, revision, false); formal(rule); return rule;
        });
    }
    @Override public Publication publish(Command command) {
        require(command != null && command.commandId() != null && command.expectedVersion() >= 0, INVALID_INPUT);
        return run("publish", write, () -> {
            UUID actor = authorize(command.quizId(), command.revisionId(), true);
            formal(command.rule());
            String canonical = format.encode(command.rule());
            String requestHash = ReleaseConfigurationFormat.hash(command.revisionId() + "|" + command.expectedVersion() + "|" + canonical);
            var receipt = repository.receipt(command, actor);
            if (receipt.isPresent()) {
                require(receipt.get().requestHash().equals(requestHash), CONFLICT);
                return replay(receipt.get().publication());
            }
            var current = repository.current(command.quizId(), command.revisionId());
            require(command.expectedVersion() == current.map(Publication::publicationVersion).orElse(0L), STALE);
            String hash = ReleaseConfigurationFormat.hash(canonical);
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Publication publication;
            if (current.isPresent() && current.get().hash().equals(hash)) publication = current.get();
            else {
                require(command.expectedVersion() < Long.MAX_VALUE, INVALID_INPUT);
                publication = new Publication(UUID.randomUUID(), command.quizId(), command.revisionId(),
                        command.expectedVersion() + 1, hash, command.rule(), now, false);
                repository.insert(publication, actor);
                repository.pointTo(publication);
            }
            repository.record(command, actor, requestHash, publication, now);
            return publication;
        });
    }
    @Override public Publication current(UUID quiz, UUID revision) {
        return run("current", read, () -> {
            authorize(quiz, revision, false);
            return repository.current(quiz, revision).orElseThrow(() -> new ReleaseConfigurationException(NOT_FOUND));
        });
    }
    @Override public Publication read(UUID quiz, UUID revision, UUID id) {
        require(id != null, INVALID_INPUT);
        return run("read", read, () -> {
            authorize(quiz, revision, false);
            return repository.read(quiz, revision, id).orElseThrow(() -> new ReleaseConfigurationException(NOT_FOUND));
        });
    }
    private UUID authorize(UUID quiz, UUID revision, boolean writing) {
        require(quiz != null && revision != null, INVALID_INPUT);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        require(auth != null && auth.isAuthenticated() && !new AuthenticationTrustResolverImpl().isAnonymous(auth), UNAUTHENTICATED);
        return source.authorize(auth.getName(), quiz, revision, writing);
    }
    private static void formal(ReleaseConfiguration rule) {
        require(rule != null && rule.family() == ResultDisclosure.Family.FORMAL_ASSESSMENT, INVALID_INPUT);
    }
    private static Publication replay(Publication p) {
        return new Publication(p.id(), p.quizId(), p.revisionId(), p.publicationVersion(), p.hash(), p.rule(), p.publishedAt(), true);
    }
    private <T> T run(String operation, TransactionTemplate tx, Supplier<T> action) {
        AttemptTransactions.requireCompatible(tx.getTransactionManager(), !tx.isReadOnly());
        String outcome = "unavailable";
        class Failure { RuntimeException reached; }
        var callback = new Failure();
        try {
            T value = tx.execute(status -> {
                try { return action.get(); }
                catch (RuntimeException failure) {
                    AttemptTransactionException.preserve(failure);
                    callback.reached = failure instanceof ReleaseConfigurationException ? failure : new ReleaseConfigurationException(UNAVAILABLE);
                    throw callback.reached;
                }
            });
            outcome = value instanceof Publication p && p.replayed() ? "replayed" : "success";
            return value;
        } catch (RuntimeException failure) {
            AttemptTransactionException.preserve(failure);
            var safe = failure == callback.reached ? (ReleaseConfigurationException) failure : new ReleaseConfigurationException(UNKNOWN_OUTCOME);
            outcome = safe.reason().name().toLowerCase(Locale.ROOT);
            log.warn("Release configuration rejected: operation={}, version=1, reason={}", operation, safe.reason());
            throw safe;
        } finally {
            try { metrics.counter("quizmaker.result.release.configuration", "operation", operation, "version", "1", "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* Telemetry never changes publication or its failure. */ }
        }
    }
}
