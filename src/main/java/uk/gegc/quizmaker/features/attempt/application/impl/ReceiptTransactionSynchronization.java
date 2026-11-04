package uk.gegc.quizmaker.features.attempt.application.impl;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;

import java.util.LinkedHashMap;

import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.Reason.UNKNOWN_OUTCOME;

/** Protects receipt transaction callbacks before Spring can log their diagnostics. */
final class ReceiptTransactionSynchronization implements TransactionSynchronization {
    private final TransactionSynchronization delegate;
    private final int order;

    private ReceiptTransactionSynchronization(TransactionSynchronization delegate) {
        this.delegate = delegate;
        this.order = delegate.getOrder();
    }

    static void protectRegistered() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        try {
            var current = TransactionSynchronizationManager.getSynchronizations();
            if (current.stream().allMatch(ReceiptTransactionSynchronization.class::isInstance)) return;
            // Preserve Spring's ordering and Set semantics, including re-registration of a delegate.
            var protectedCallbacks = new LinkedHashMap<TransactionSynchronization, TransactionSynchronization>();
            for (var callback : current) {
                var delegate = callback instanceof ReceiptTransactionSynchronization protectedCallback
                        ? protectedCallback.delegate : callback;
                protectedCallbacks.putIfAbsent(delegate, callback instanceof ReceiptTransactionSynchronization
                        ? callback : new ReceiptTransactionSynchronization(delegate));
            }
            // Replace only the synchronization registry, never transaction metadata or resources.
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            protectedCallbacks.values().forEach(TransactionSynchronizationManager::registerSynchronization);
        } catch (RuntimeException failure) {
            throw new ReceiptException(UNKNOWN_OUTCOME);
        }
    }

    private void invoke(Runnable callback) {
        try { callback.run(); }
        catch (RuntimeException failure) { throw new ReceiptException(UNKNOWN_OUTCOME); }
        finally {
            // Spring takes a fresh snapshot for each phase. Protect callbacks registered by a
            // callback before the next phase; afterCompletion has already cleared the registry.
            protectRegistered();
        }
    }

    @Override public int getOrder() { return order; }
    @Override public void suspend() { invoke(delegate::suspend); }
    @Override public void resume() { invoke(delegate::resume); }
    @Override public void flush() { invoke(delegate::flush); }
    @Override public void savepoint(Object savepoint) { invoke(() -> delegate.savepoint(savepoint)); }
    @Override public void savepointRollback(Object savepoint) { invoke(() -> delegate.savepointRollback(savepoint)); }
    @Override public void beforeCommit(boolean readOnly) { invoke(() -> delegate.beforeCommit(readOnly)); }
    @Override public void beforeCompletion() { invoke(delegate::beforeCompletion); }
    @Override public void afterCommit() { invoke(delegate::afterCommit); }
    @Override public void afterCompletion(int status) { invoke(() -> delegate.afterCompletion(status)); }
}
