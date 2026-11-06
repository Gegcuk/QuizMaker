package uk.gegc.quizmaker.shared.exception;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;

/** Internal, content-free signal crossing feature boundaries before SQL diagnostics are discarded. */
public final class DatabaseConflictException extends RuntimeException {
    public enum Kind { DEADLOCK, LOCK_TIMEOUT }

    private final Kind kind;

    private DatabaseConflictException(Kind kind) {
        // Neither cause nor suppressed exceptions can later acquire driver/content diagnostics.
        super("Database conflict: " + kind, null, false, true);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    public String outcome() { return kind == Kind.DEADLOCK ? "retryable" : "in_progress"; }

    /** Classification only: no retry, transaction recovery or inference from arbitrary messages. */
    public static Optional<DatabaseConflictException> from(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        if (failure != null) pending.add(failure);
        while (!pending.isEmpty()) {
            var cause = pending.removeFirst();
            if (!visited.add(cause)) continue;
            if (cause instanceof DatabaseConflictException conflict)
                return Optional.of(new DatabaseConflictException(conflict.kind));
            if (cause instanceof SQLException sql) {
                if (sql.getErrorCode() == 1213) return Optional.of(new DatabaseConflictException(Kind.DEADLOCK));
                if (sql.getErrorCode() == 1205) return Optional.of(new DatabaseConflictException(Kind.LOCK_TIMEOUT));
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (cause.getCause() != null) pending.add(cause.getCause());
        }
        return Optional.empty();
    }
}
