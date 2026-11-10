package uk.gegc.quizmaker.shared.transaction;

import org.springframework.dao.DataAccessException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.DefaultTransactionStatus;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

/** Redacts JPA commit/rollback failures before Spring's completion logging and dispatch. */
public class SafeJpaTransactionManager extends JpaTransactionManager {
    private static final String MESSAGE = "Database transaction completion failed";

    @Override protected void doCommit(DefaultTransactionStatus status) {
        try { super.doCommit(status); }
        catch (RuntimeException failure) { throw safe(failure); }
    }

    @Override protected void doRollback(DefaultTransactionStatus status) {
        try { super.doRollback(status); }
        catch (RuntimeException failure) { throw safe(failure); }
    }

    private static RuntimeException safe(RuntimeException failure) {
        if (failure instanceof DataAccessException data) return SafeDataAccess.sanitize(data);
        Throwable conflict = DatabaseConflictException.from(failure).orElse(null);
        // These categories select different commit/rollback/UNKNOWN branches in the superclass.
        if (failure instanceof UnexpectedRollbackException) return new UnexpectedRollbackException(MESSAGE, conflict);
        if (failure instanceof HeuristicCompletionException heuristic)
            return new HeuristicCompletionException(heuristic.getOutcomeState(), conflict);
        if (failure instanceof TransactionSystemException) return new TransactionSystemException(MESSAGE, conflict);
        if (failure instanceof TransactionTimedOutException) return new TransactionTimedOutException(MESSAGE, conflict);
        if (failure instanceof TransactionException) return new CompletionTransactionException(conflict);
        return new CompletionException(conflict);
    }

    private static final class CompletionTransactionException extends TransactionException {
        private CompletionTransactionException(Throwable conflict) { super(MESSAGE, conflict); }
    }
    private static final class CompletionException extends RuntimeException {
        private CompletionException(Throwable conflict) { super(MESSAGE, conflict, false, true); }
    }
}
