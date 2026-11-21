package uk.gegc.quizmaker.features.learning.application;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.learning.domain.model.LearningForm;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import java.util.List;
import java.util.UUID;

public interface LearningItemMaterial {
    List<LearningForm.Item> describe(UUID learner, Source source, List<UUID> questions);
    String validateResponse(LearningSession session, LearningForm.Item item, JsonNode response);
}
