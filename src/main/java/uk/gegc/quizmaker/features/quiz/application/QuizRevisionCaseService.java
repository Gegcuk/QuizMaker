package uk.gegc.quizmaker.features.quiz.application;

import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.util.UUID;

/**
 * Internal complete-aggregate publication for an already authorized author caller, not an HTTP command.
 * trustedOwnerId is resolved by that boundary. Publishing a case grants no participant access.
 * Identical replay is read-only; conflicting identity fails. Joins the caller's transaction.
 */
public interface QuizRevisionCaseService {
    void publish(UUID trustedOwnerId, QuizRevision revision);
}
