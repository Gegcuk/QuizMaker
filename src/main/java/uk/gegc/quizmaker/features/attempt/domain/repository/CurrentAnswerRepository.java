package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.AcceptedAnswerFact;

import java.util.List;
import java.util.UUID;

public interface CurrentAnswerRepository {
    /** Decode the database-owned projection selected under the attempt lock; never read child rows. */
    List<AcceptedAnswerFact> readCurrent(UUID attemptId, String projection);
}
