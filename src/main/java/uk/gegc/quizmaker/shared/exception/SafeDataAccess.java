package uk.gegc.quizmaker.shared.exception;

import org.springframework.dao.*;

/** Discards SQL/payload diagnostics before a repository's transaction interceptor sees a failure. */
public final class SafeDataAccess {
    private static final String MESSAGE = "Database operation failed";
    private SafeDataAccess() { }

    /** Keep caller-visible Spring categories and bounded conflict evidence, never the driver graph. */
    @SuppressWarnings("deprecation")
    public static DataAccessException sanitize(DataAccessException failure) {
        Throwable conflict = DatabaseConflictException.from(failure).orElse(null);
        if (failure instanceof DuplicateKeyException) return new DuplicateKeyException(MESSAGE, conflict);
        if (failure instanceof DataIntegrityViolationException) return new DataIntegrityViolationException(MESSAGE, conflict);
        if (failure instanceof CannotAcquireLockException) return new CannotAcquireLockException(MESSAGE, conflict);
        if (failure instanceof DeadlockLoserDataAccessException) return new DeadlockLoserDataAccessException(MESSAGE, conflict);
        if (failure instanceof PessimisticLockingFailureException) return new PessimisticLockingFailureException(MESSAGE, conflict);
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

    private static final class Unavailable extends NonTransientDataAccessException {
        private Unavailable(Throwable conflict) { super(MESSAGE, conflict); }
    }
}
