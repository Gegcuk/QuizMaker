package uk.gegc.quizmaker.features.learning.domain.repository;

import uk.gegc.quizmaker.features.learning.domain.model.*;
import java.util.List;
import java.util.Optional;

/** Persistence-only, within an already authorized transaction; no update/delete or lifecycle API. */
public interface LearningFormRepository {
    void lockSession(LearningSession session);
    Optional<LearningForm> find(LearningSession session);
    void verifyItems(LearningSession session, List<LearningForm.Item> items);
    void insert(LearningForm form);
}
