package uk.gegc.quizmaker.shared.exception;

import org.springframework.dao.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Discards SQL/payload diagnostics before a repository's transaction interceptor sees a failure. */
public final class SafeDataAccess {
    private static final String MESSAGE = "Database operation failed";
    private SafeDataAccess() { }

    /** Keep caller-visible Spring categories and bounded conflict evidence, never the driver graph. */
    @SuppressWarnings("deprecation")
    public static DataAccessException sanitize(DataAccessException failure) {
        Throwable conflict = DatabaseConflictException.from(failure).orElse(null);
        if (failure instanceof DuplicateKeyException) return new DuplicateKeyException(MESSAGE + ": duplicate key", conflict);
        if (failure instanceof DataIntegrityViolationException) return new DataIntegrityViolationException(integrityMessage(failure), conflict);
        if (failure instanceof CannotAcquireLockException) return new CannotAcquireLockException(MESSAGE, conflict);
        if (failure instanceof DeadlockLoserDataAccessException) return new DeadlockLoserDataAccessException(MESSAGE, conflict);
        if (failure instanceof PessimisticLockingFailureException) return new PessimisticLockingFailureException(MESSAGE, conflict);
        if (failure instanceof ObjectOptimisticLockingFailureException) return new ObjectOptimisticLockingFailureException(MESSAGE, conflict);
        if (failure instanceof OptimisticLockingFailureException) return new OptimisticLockingFailureException(MESSAGE, conflict);
        if (failure instanceof QueryTimeoutException) return new QueryTimeoutException(MESSAGE, conflict);
        if (failure instanceof TransientDataAccessResourceException) return new TransientDataAccessResourceException(MESSAGE, conflict);
        if (failure instanceof RecoverableDataAccessException) return new RecoverableDataAccessException(MESSAGE, conflict);
        if (failure instanceof DataAccessResourceFailureException) return new DataAccessResourceFailureException(MESSAGE, conflict);
        if (failure instanceof PermissionDeniedDataAccessException) return new PermissionDeniedDataAccessException(MESSAGE, conflict);
        if (failure instanceof InvalidDataAccessApiUsageException) return new InvalidDataAccessApiUsageException(MESSAGE, conflict);
        if (failure instanceof InvalidDataAccessResourceUsageException) return new InvalidDataAccessResourceUsageException(MESSAGE, conflict);
        if (failure instanceof ConcurrencyFailureException) return new ConcurrencyFailureException(MESSAGE, conflict);
        if (failure instanceof TransientDataAccessException) return new TransientDataAccessResourceException(MESSAGE, conflict);
        return new Unavailable(conflict);
    }

    private static String integrityMessage(Throwable failure) {
        // Keep the existing advice's user-facing categories without retaining driver text or names.
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof SQLException sql) {
                String category = switch (sql.getErrorCode()) {
                    case 1062 -> "duplicate key";
                    case 1451, 1452 -> "foreign key constraint";
                    case 1048 -> "not-null";
                    case 3819 -> "check constraint";
                    default -> null;
                };
                if (category != null) return MESSAGE + ": " + category;
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (current.getCause() != null) pending.add(current.getCause());
        }
        // Also retain a previously sanitized category when a failure crosses another safe boundary.
        for (String category : new String[]{"duplicate key", "foreign key constraint", "not-null", "check constraint"})
            if ((MESSAGE + ": " + category).equals(failure.getMessage())) return MESSAGE + ": " + category;
        return MESSAGE;
    }

    private static final class Unavailable extends NonTransientDataAccessException {
        private Unavailable(Throwable conflict) { super(MESSAGE, conflict); }
    }
}
