package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.*;

import java.util.UUID;

/**
 * Dark authenticated commands only; no current route is wired here. Consuming handlers resolve
 * identity and current permission/ownership/visibility before EVERY lookup, including replay.
 * The callbacks must use the same database/transaction manager, never REQUIRES_NEW, external I/O,
 * autonomous commits or after-commit business writes. #600/#543/#529 own actual handler composition.
 *
 * <p>V2 composition requires actual READ_COMMITTED; standalone owners set it explicitly.
 * See {@link uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions}
 * for ownership, rejection and JPA caller obligations. Legacy contracts are separate.
 */
public interface CommandReceiptService {
    /**
     * REQUIRED READ_COMMITTED transaction: authorize, reserve, execute once, persist acknowledgement together.
     * Returned is not committed when joining an outer transaction. Publish success only after
     * its commit. Unknown commit outcome requires a fresh call with EXACTLY the same request.
     * No automatic retry/takeover. Concurrent duplicates wait on MySQL; timeout is IN_PROGRESS,
     * deadlock is RETRYABLE, connection/commit uncertainty is UNKNOWN_OUTCOME.
     * Business denials use bounded ReceiptException reasons; unexpected callback errors are sanitized.
     * Runtime failures in transaction completion (including after-commit observers) are UNKNOWN_OUTCOME.
     * Synchronizations registered by the time this call exits are protected before Spring logs them,
     * including on later outer completion. Synchronizations the outer owner registers after return
     * and global transaction-manager listeners remain outside this local diagnostic boundary.
     */
    Result execute(Request request, Authorization authorization, Work work);

    @FunctionalInterface interface Authorization { void check(); }
    @FunctionalInterface interface Work { Acknowledgement execute(UUID commandId); }

    /**
     * D08 primitive for #699, NOT a scheduler or a hold/erasure authorization service. Requires an
     * existing writable READ_COMMITTED transaction in which the caller has locked/verified purpose, current cleanup authority
     * and absence of holds. Unknown classification/hold state must never call this method.
     * Checks authoritative terminal state/version and terminal+30d; deletes at most limit receipts,
     * never attempt/answer/result/trace facts. After deletion the retry guarantee has ended; consumer
     * natural uniqueness remains mandatory. No fabricated legacy receipts or age-based history purge.
     */
    int purgeExpired(UUID attemptId, long expectedStateVersion, int limit);
}
