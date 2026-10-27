package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleRead;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleReference;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;

import java.util.UUID;

/**
 * Dark target persistence for trusted server callers. An attempt ID grants no access: consuming
 * commands must resolve engine binding, authorization and all #527 guards before writing.
 * No command is executed and no response, grade, result or receipt is persisted here.
 */
public interface AttemptLifecycleService {
    /** Creates only CREATED/NOT_STARTED at row version zero; duplicate IDs conflict. */
    LifecycleState initialize(UUID attemptId);

    /** Ordinary snapshot read; missing target state is an error, never implicit legacy. */
    LifecycleRead read(LifecycleReference reference);

    /** Requires an existing caller transaction; its root lock lasts until that transaction ends. */
    LifecycleState lock(UUID attemptId);

    /**
     * Joins the caller transaction and atomically increments its expected storage version.
     * Successful return does not commit an outer transaction. Persistence failure can mean an
     * unknown commit outcome: reconcile by a fresh read; #599 owns command replay identity.
     */
    LifecycleState save(UUID attemptId, long expectedVersion, State next);
}
