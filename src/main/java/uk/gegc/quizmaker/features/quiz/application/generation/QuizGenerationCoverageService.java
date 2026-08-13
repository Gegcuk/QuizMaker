package uk.gegc.quizmaker.features.quiz.application.generation;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface QuizGenerationCoverageService {

    void saveDecision(UUID jobId, GenerationCoverageSnapshot snapshot);

    Map<UUID, GenerationCoverageSnapshot> findByJobIds(Collection<UUID> jobIds);
}
