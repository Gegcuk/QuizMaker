package uk.gegc.quizmaker.features.quiz.application.generation;

import java.util.UUID;

public record GenerationWorkClaim(UUID jobId, long fence, UUID documentId, String requestData,
                                  java.time.Instant deadline, int maxConcurrentChunks) {
    public GenerationWorkClaim(UUID jobId, long fence, UUID documentId, String requestData) {
        this(jobId, fence, documentId, requestData, null, 2);
    }
}
