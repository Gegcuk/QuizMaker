package uk.gegc.quizmaker.features.quiz.application;

import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.util.UUID;

/** Internal boundary for a caller that has already authorized this quiz and its source content. */
public interface QuizScenarioRevisionService {
    /** Validates and stores a new executable scenario revision, or returns the exact saved revision on repeat. */
    QuizRevision publish(UUID trustedOwnerId, QuizRevision revision);
}
