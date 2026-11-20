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
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningDueSelection.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningDueSelectionRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.require;

@Service
@PreAuthorize("isAuthenticated()")
public class LearningDueSelectionServiceImpl implements LearningDueSelectionService {
    private final LearningDueSelectionRepository repository;
    private final AuthenticatedUserLookup users;
    private final List<LearningSessionSourceVerifier> sources;
    private final LearningDueSelectionConfig config;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;

    public LearningDueSelectionServiceImpl(LearningDueSelectionRepository repository, AuthenticatedUserLookup users,
            List<LearningSessionSourceVerifier> sources, LearningDueSelectionConfig config, Clock clock,
            MeterRegistry metrics, PlatformTransactionManager manager) {
        this.repository = repository; this.users = users; this.sources = List.copyOf(sources);
        this.config = config; this.clock = clock; this.metrics = metrics;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(true);
    }

    @Override public LearningDueSelection select(Select request) {
        require(!TransactionSynchronizationManager.isActualTransactionActive()
                || Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_READ_COMMITTED), UNSUPPORTED_TRANSACTION);
        String outcome = "persistence_unavailable";
        try {
            var result = transaction.execute(status -> {
                try { return selectWithinTransaction(request); }
                catch (LearningScheduleException failure) { throw new LearningScheduleException(failure.reason()); }
                catch (RuntimeException failure) {
                    throw DatabaseConflictException.from(failure).<RuntimeException>map(value -> value)
                            .orElseGet(() -> new LearningScheduleException(PERSISTENCE_UNAVAILABLE));
                }
            });
            outcome = result.outcome().name().toLowerCase(Locale.ROOT);
            return result;
        } catch (LearningScheduleException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT); throw failure;
        } catch (RuntimeException failure) {
            var conflict = DatabaseConflictException.from(failure);
            if (conflict.isPresent()) { outcome = conflict.get().outcome(); throw conflict.get(); }
            throw new LearningScheduleException(PERSISTENCE_UNAVAILABLE);
        } finally {
            try { metrics.counter("quizmaker.learning.due", "version", "1", "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* Optional telemetry cannot change a read result. */ }
        }
    }

    private LearningDueSelection selectWithinTransaction(Select request) {
        require(request != null && request.source() != null, INVALID_INPUT);
        require(request.version() == 1, UNSUPPORTED_VERSION);
        Integer maximum = config.getMaxBatchSize();
        require(maximum != null && maximum > 0 && maximum < Integer.MAX_VALUE, SELECTION_UNAVAILABLE);
        require(request.limit() > 0 && request.limit() <= maximum, INVALID_INPUT);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        require(validTime(now), INVALID_TIME);
        var after = request.after();
        if (after != null) {
            require(after.version() == 1, UNSUPPORTED_VERSION);
            require(request.source().equals(after.source()) && after.questionRevisionId() != null
                    && validTime(after.dueAt()) && !after.dueAt().isAfter(now), INVALID_INPUT);
        }
        UUID learner = actor();
        require(sources.size() == 1 && sources.get(0).allows(learner, request.source()), SOURCE_UNAVAILABLE);
        require(repository.sourceExists(request.source()), SOURCE_UNAVAILABLE);
        var found = repository.find(learner, request.source(), now, after, request.limit() + 1);
        require(found != null && found.size() <= request.limit() + 1, CORRUPT);
        Item previous = after == null ? null : new Item(after.questionRevisionId(), null, after.dueAt());
        var seen = new HashSet<UUID>();
        for (var item : found) {
            require(item != null && item.questionRevisionId() != null && item.expected() != null
                    && item.expected().cycleId() != null && item.expected().cycleVersion() > 0
                    && item.expected().sequence() >= 0 && validTime(item.dueAt()), CORRUPT);
            require(!item.dueAt().isAfter(now) && seen.add(item.questionRevisionId())
                    && (previous == null || compare(previous, item) < 0), CORRUPT);
            previous = item;
        }
        boolean more = found.size() > request.limit();
        var items = List.copyOf(found.subList(0, Math.min(found.size(), request.limit())));
        Cursor next = more ? new Cursor(1, request.source(), items.get(items.size() - 1).dueAt(),
                items.get(items.size() - 1).questionRevisionId()) : null;
        Outcome result = items.isEmpty() ? Outcome.EMPTY : items.size() < request.limit() ? Outcome.PARTIAL : Outcome.READY;
        return new LearningDueSelection(1, request.source(), now, request.limit(), items, result, next);
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
        return users.findActiveUserId(name).orElseThrow(() -> new LearningScheduleException(UNAVAILABLE));
    }

    private static boolean validTime(Instant time) {
        return time != null && time.equals(time.truncatedTo(ChronoUnit.MICROS))
                && !time.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"));
    }
    private static int compare(Item left, Item right) {
        int time = left.dueAt().compareTo(right.dueAt());
        if (time != 0) return time;
        int high = Long.compareUnsigned(left.questionRevisionId().getMostSignificantBits(), right.questionRevisionId().getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.questionRevisionId().getLeastSignificantBits(), right.questionRevisionId().getLeastSignificantBits());
    }
}
