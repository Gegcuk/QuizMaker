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
    /** Keyed by question revision, using its lowest authored position in this publication. */
    Map<UUID, Item> firstOccurrences(UUID learner, Source source, List<UUID> questionRevisionIds);
    /** Keyed by the exact occurrence ID retained by the form. */
    Map<UUID, Item> savedOccurrences(UUID learner, Source source, List<UUID> sourceItemIds);
}
