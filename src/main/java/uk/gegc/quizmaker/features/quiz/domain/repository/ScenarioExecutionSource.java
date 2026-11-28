package uk.gegc.quizmaker.features.quiz.domain.repository;

import java.util.Map;
import java.util.UUID;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph;

/** Already-authorized revision metadata. Only the current and next occurrence need question content. */
public interface ScenarioExecutionSource {
    record Source(ScenarioGraph graph, Map<UUID, UUID> questions) {
        public Source { questions = Map.copyOf(questions); }
        @Override public String toString() { return "ScenarioExecutionSource"; }
    }

    Source readGraph(UUID owner, UUID quiz, UUID revision);
    QuestionRevision readQuestion(UUID owner, UUID revision, UUID sourceItem);
}
