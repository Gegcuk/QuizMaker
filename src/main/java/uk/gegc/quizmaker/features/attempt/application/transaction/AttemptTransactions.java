package uk.gegc.quizmaker.features.attempt.application.transaction;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactionException.Reason.*;

/**
 * RC ownership/participation for v2 execution only; no pool or global manager changes.
 * Owners use REQUIRED unless their established worker contract requires an independent transaction.
 * Callers must flush relevant JPA inputs before JDBC work and refresh/reload affected managed
 * outputs afterward. This boundary never flushes or clears the persistence context automatically.
 * Participating results remain provisional until the owner commits. Unsupported composition
 * fails with a bounded {@link AttemptTransactionException} before identity/domain work.
 */
public final class AttemptTransactions {
    private static final Object VALIDATED = AttemptTransactions.class;
    private AttemptTransactions() { }

    public static TransactionTemplate readCommitted(PlatformTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    /** Call before the owning template and before identity/domain reads or receipt reservation. */
    public static void requireCompatible(PlatformTransactionManager manager, boolean write) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return;
        if (write && TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new AttemptTransactionException(READ_ONLY);
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null) {
            requireReadCommitted(isolation);
            return;
        }
        // DEFAULT is not proof of RC. Inspect only the enlisted connection, never a second one.
        ConnectionHolder proof;
        try {
            DataSource source = manager instanceof JpaTransactionManager jpa ? jpa.getDataSource()
                    : manager instanceof DataSourceTransactionManager jdbc ? jdbc.getDataSource() : null;
            if (source == null || !TransactionSynchronizationManager.isSynchronizationActive()
                    || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder))
                throw new AttemptTransactionException(UNRESOLVABLE_ISOLATION);
            var validated = TransactionSynchronizationManager.getResource(VALIDATED);
            if (validated == holder) return;
            if (validated != null) throw new AttemptTransactionException(UNRESOLVABLE_ISOLATION);
            requireReadCommitted(holder.getConnection().getTransactionIsolation());
            proof = holder;
        } catch (AttemptTransactionException known) { throw known; }
        catch (Exception unavailable) { throw new AttemptTransactionException(UNRESOLVABLE_ISOLATION); }
        // One physical lookup per DEFAULT transaction, including repeated facts/items. Suspension
        // must hide this proof from REQUIRES_NEW and restore it with its own outer transaction.
        TransactionSynchronizationManager.bindResource(VALIDATED, proof);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void suspend() { clear(); }
            @Override public void resume() { TransactionSynchronizationManager.bindResource(VALIDATED, proof); }
            @Override public void afterCompletion(int status) { clear(); }
            private void clear() {
                if (TransactionSynchronizationManager.getResource(VALIDATED) == proof)
                    TransactionSynchronizationManager.unbindResource(VALIDATED);
            }
        });
    }

    private static void requireReadCommitted(int isolation) {
        if (isolation != TransactionDefinition.ISOLATION_READ_COMMITTED)
            throw new AttemptTransactionException(INCOMPATIBLE_ISOLATION);
    }
}
