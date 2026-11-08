package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.*;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft.Reference;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.AnswerDraftRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import static uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraftException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraftException.require;

@Service
public class AnswerDraftStoreImpl implements AnswerDraftStore {
    private static final int MAX_BYTES = 65_536; // Existing v1 response bound, not a new product quota.
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final AnswerDraftRepository repository;
    private final AttemptLifecycleService lifecycle;
    private final QuestionContractRegistry contracts;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry metrics;

    public AnswerDraftStoreImpl(AnswerDraftRepository repository, AttemptLifecycleService lifecycle,
            QuestionContractRegistry contracts, PlatformTransactionManager manager, Clock clock, MeterRegistry metrics) {
        this.repository = repository; this.lifecycle = lifecycle; this.contracts = contracts;
        this.transaction = new TransactionTemplate(manager); this.clock = clock; this.metrics = metrics;
    }

    @Override public Optional<AnswerDraft> read(UUID user, Reference ref) {
        return execute("read", () -> {
            input(user, ref);
            if (!repository.lockSubject(user, ref.attemptId())) return Optional.empty();
            var scope = repository.scope(ref);
            var contract = contracts.resolve(scope.binding()).response();
            var saved = repository.read(ref);
            saved.filter(d -> d.status() == AnswerDraft.Status.PRESENT).ifPresent(d -> {
                try { validate(scope, contract, d.normalizedResponse()); }
                catch (AnswerDraftException failure) {
                    throw new AnswerDraftException(failure.reason() == UNSUPPORTED ? UNSUPPORTED : CORRUPT);
                }
                try { io.micrometer.core.instrument.DistributionSummary.builder("attempt.draft.age")
                        .baseUnit("seconds").tag("version", "1").register(metrics)
                        .record(Math.max(0, Duration.between(d.updatedAt(), clock.instant()).toSeconds())); }
                catch (RuntimeException ignored) { /* optional telemetry */ }
            });
            return saved;
        });
    }

    @Override public AnswerDraft save(UUID user, Reference ref, long expected, String value) {
        return mutate(user, ref, expected, value, false);
    }

    @Override public AnswerDraft delete(UUID user, Reference ref, long expected) {
        return mutate(user, ref, expected, null, true);
    }

    private AnswerDraft mutate(UUID user, Reference ref, long expected, String value, boolean delete) {
        return execute(delete ? "delete" : expected == 0 ? "create" : "update", () -> {
            input(user, ref);
            require(expected >= 0 && expected < Long.MAX_VALUE, INVALID_INPUT);
            require(repository.lockSubject(user, ref.attemptId()), NOT_FOUND);
            // Match acceptance/submission lock order. No lifecycle state is changed by this store.
            var state = lifecycle.lock(ref.attemptId());
            require(state.state().execution() == AttemptLifecycle.Execution.IN_PROGRESS, INACTIVE);
            var scope = repository.scope(ref);
            var contract = contracts.resolve(scope.binding()).response();
            require(!repository.accepted(ref), INACTIVE);
            // Missing-row locking probes take shared gap locks under RR and can deadlock
            // unrelated first inserts. The unique key checks an expected-zero insert directly.
            var old = expected == 0 ? Optional.<AnswerDraft>empty() : repository.read(ref);
            require(old.map(AnswerDraft::version).orElse(0L) == expected, CONFLICT);
            String normalized = delete ? null : validate(scope, contract, value);
            var now = AnswerDraft.time(clock.instant());
            require(!now.isBefore(state.startedAt()), INVALID_INPUT);
            if (old.isPresent()) require(!now.isBefore(old.get().updatedAt()), INVALID_INPUT);
            var next = new AnswerDraft(ref, expected + 1, delete ? AnswerDraft.Status.DELETED : AnswerDraft.Status.PRESENT,
                    normalized, old.map(AnswerDraft::createdAt).orElse(now), now);
            if (old.isEmpty()) repository.insert(next);
            else require(repository.compareAndSet(expected, next), CONFLICT);
            return next;
        });
    }

    private static void input(UUID user, Reference ref) { require(user != null && ref != null, INVALID_INPUT); }

    private String validate(AnswerDraftRepository.Scope scope,
            uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract contract, String raw) {
        require(raw != null && raw.length() <= MAX_BYTES && raw.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, INVALID_INPUT);
        try {
            JsonNode value = JSON.readTree(raw);
            require(value != null && value.isObject(), INVALID_INPUT);
            contract.validate(scope.question(), value);
            String normalized = JSON.writeValueAsString(value);
            require(normalized.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, INVALID_INPUT);
            return normalized;
        } catch (java.io.IOException | QuestionContractException failure) {
            throw new AnswerDraftException(INVALID_INPUT);
        }
    }

    private <T> T execute(String operation, Supplier<T> action) {
        String outcome = "unavailable";
        try {
            var result = transaction.execute(status -> {
                try { return action.get(); }
                catch (RuntimeException failure) { throw sanitized(failure); }
            });
            outcome = "returned";
            return result;
        } catch (RuntimeException failure) {
            var safe = sanitized(failure); outcome = safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { metrics.counter("attempt.draft.storage", "version", "1", "operation", operation, "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* telemetry never changes commit or rollback */ }
        }
    }

    private static AnswerDraftException sanitized(RuntimeException failure) {
        if (failure instanceof AnswerDraftException known) return new AnswerDraftException(known.reason());
        var conflict = DatabaseConflictException.from(failure);
        if (conflict.isPresent()) return new AnswerDraftException(conflict.get().kind() == DatabaseConflictException.Kind.DEADLOCK ? RETRYABLE : BUSY);
        if (failure instanceof DuplicateKeyException) return new AnswerDraftException(CONFLICT);
        if (failure instanceof ContractResolutionException) return new AnswerDraftException(UNSUPPORTED);
        if (failure instanceof LifecycleStateException state) return new AnswerDraftException(switch (state.reason()) {
            case STATE_UNAVAILABLE -> NOT_FOUND;
            case CORRUPT_STATE, UNSUPPORTED_VERSION -> CORRUPT;
            default -> UNAVAILABLE;
        });
        if (failure instanceof IllegalArgumentException
                || failure instanceof uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException)
            return new AnswerDraftException(CORRUPT);
        return new AnswerDraftException(UNAVAILABLE);
    }
}
