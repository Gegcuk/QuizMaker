package uk.gegc.quizmaker.features.learning.application;

import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Trusted source projection. The caller verifies current display access to the exact publication through commit. */
public interface LearningPublicationItems {
    record Item(UUID sourceItemId, QuestionRevision question, QuestionContractBinding binding) { }
    Map<UUID, Item> selected(UUID learner, Source source, List<UUID> questionRevisionIds);
}
