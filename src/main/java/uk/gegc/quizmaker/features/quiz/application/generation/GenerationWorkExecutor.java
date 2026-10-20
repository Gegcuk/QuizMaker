package uk.gegc.quizmaker.features.quiz.application.generation;

import java.util.UUID;

public interface GenerationWorkExecutor {
    void execute(UUID jobId);
    boolean executeNext();
}
