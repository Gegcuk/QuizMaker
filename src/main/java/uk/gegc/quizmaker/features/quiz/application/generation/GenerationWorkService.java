package uk.gegc.quizmaker.features.quiz.application.generation;

import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface GenerationWorkService {
    void enroll(QuizGenerationJob job);
    boolean isManaged(UUID jobId);
    List<UUID> findDue();
    Optional<GenerationWorkClaim> claim(UUID jobId);
    void heartbeat(GenerationWorkClaim claim);
    <T> T protect(GenerationWorkClaim claim, Supplier<T> action);
    void finished(GenerationWorkClaim claim);
    void failed(GenerationWorkClaim claim);
}
