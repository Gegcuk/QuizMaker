package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.CommandReceiptService;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactionException;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.features.attempt.domain.repository.CommandReceiptRepository;
import uk.gegc.quizmaker.features.attempt.infra.receipt.ReceiptDatabaseFailure;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.require;

@Service
public class CommandReceiptServiceImpl implements CommandReceiptService {
    private final CommandReceiptRepository repository;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate write;
    private final TransactionTemplate mandatory;

    public CommandReceiptServiceImpl(CommandReceiptRepository repository, PlatformTransactionManager transactions,
                                     Clock clock, MeterRegistry metrics) {
        this.repository = repository; this.clock = clock; this.metrics = metrics;
        write = AttemptTransactions.readCommitted(transactions);
        mandatory = new TransactionTemplate(transactions);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    public Result execute(Request request, Authorization authorization, Work work) {
        return run("execute", write, () -> {
            require(request != null && authorization != null && work != null, INVALID_INPUT);
            // Before any protected existence, replay, conflict, expiry or version inspection.
            try { authorization.check(); }
            catch (AttemptTransactionException failure) { throw new AttemptTransactionException(failure.reason()); }
            catch (DatabaseConflictException failure) { throw databaseFailure(failure); }
            catch (RuntimeException failure) { throw new ReceiptException(ACCESS_DENIED); }
            var now = CommandReceipt.time(clock.instant());
            UUID candidateId = UUID.randomUUID();
            var row = repository.reserve(request, candidateId, now);
            require(row.request().equals(request), CONFLICT);
            if (row.committed()) {
                verify(request, row.acknowledgement());
                return new Result(new Stored(row.id(), row.request(), row.acknowledgement(), row.recordedAt()), true);
            }
            // A persisted orphan or reentrant call never becomes an unproved execution lease.
            require(row.id().equals(candidateId), IN_PROGRESS);
            var acknowledgement = work.execute(candidateId);
            require(acknowledgement != null, COMMAND_DENIED);
            verify(request, acknowledgement);
            repository.complete(candidateId, acknowledgement);
            return new Result(new Stored(candidateId, request, acknowledgement, row.recordedAt()), false);
        });
    }

    private void verify(Request request, Acknowledgement acknowledgement) {
        var context = repository.context(acknowledgement.attemptId());
        require(context.userId().equals(request.userId()), CONTEXT_UNAVAILABLE);
        require(context.stateVersion() >= acknowledgement.stateVersion(), CORRUPT_RECEIPT);
        require(!CommandReceipt.expired(context.terminalAt(), clock.instant()), EXPIRED);
    }

    @Override
    public int purgeExpired(UUID attemptId, long expectedStateVersion, int limit) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), TRANSACTION_REQUIRED);
        return run("purge", mandatory, () -> {
            require(attemptId != null && expectedStateVersion > 0 && limit > 0
                    && limit <= CommandReceipt.MAX_CLEANUP_BATCH, INVALID_INPUT);
            var context = repository.context(attemptId);
            require(context.stateVersion() == expectedStateVersion, STALE_VERSION);
            if (!CommandReceipt.expired(context.terminalAt(), clock.instant())) return 0;
            return repository.delete(attemptId, limit);
        });
    }

    private <T> T run(String operation, TransactionTemplate transaction, Supplier<T> action) {
        AttemptTransactions.requireCompatible(transaction.getTransactionManager(), true);
        var sample = Timer.start();
        String outcome = "unknown_outcome";
        class Execution { boolean entered; RuntimeException failure; }
        var execution = new Execution();
        try {
            T result = transaction.execute(status -> {
                execution.entered = true;
                ReceiptTransactionSynchronization.protectRegistered();
                try { return action.get(); }
                // Sanitize BEFORE TransactionTemplate can log rollback diagnostics. Preserve only
                // fixed reasons; never retain callback/driver cause, suppressed errors or messages.
                catch (RuntimeException failure) {
                    execution.failure = failure instanceof AttemptTransactionException invalid ? new AttemptTransactionException(invalid.reason())
                            : failure instanceof ReceiptException receipt ? new ReceiptException(receipt.reason())
                            : (failure instanceof DataAccessException || failure instanceof DatabaseConflictException)
                            ? databaseFailure(failure)
                            : new ReceiptException(COMMAND_DENIED);
                    throw execution.failure;
                } finally {
                    // Spring runs these callbacks later, including when we join an outer transaction.
                    ReceiptTransactionSynchronization.protectRegistered();
                }
            });
            outcome = result instanceof Result r && r.replayed() ? "replay_returned" : "returned";
            return result;
        } catch (RuntimeException failure) {
            if (failure == execution.failure) AttemptTransactionException.preserve(failure);
            // Only the exact failure emitted by the action has a known execution outcome.
            // Completion can fail even after commit, regardless of the exception's type/reason.
            var reason = failure == execution.failure ? ((ReceiptException) execution.failure).reason()
                    : !execution.entered && failure instanceof CannotCreateTransactionException ? PERSISTENCE_UNAVAILABLE
                    : UNKNOWN_OUTCOME;
            outcome = reason.name().toLowerCase(Locale.ROOT);
            throw new ReceiptException(reason);
        } finally {
            try {
                sample.stop(Timer.builder("attempt.command.receipt").tags("version", "1", "operation", operation,
                        "outcome", outcome).register(metrics));
            } catch (RuntimeException ignored) { /* optional telemetry never changes command authority */ }
        }
    }

    private static ReceiptException databaseFailure(RuntimeException failure) {
        return new ReceiptException(ReceiptDatabaseFailure.reason(failure));
    }
}
