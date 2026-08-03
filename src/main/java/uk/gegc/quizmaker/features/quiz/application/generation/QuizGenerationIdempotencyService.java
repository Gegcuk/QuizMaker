package uk.gegc.quizmaker.features.quiz.application.generation;

import uk.gegc.quizmaker.features.quiz.domain.model.GenerationOperationState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationOperationType;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOperation;

import java.util.UUID;

public interface QuizGenerationIdempotencyService {

    QuizGenerationOperation claim(
            UUID userId,
            GenerationOperationType operationType,
            String idempotencyKey,
            GenerationRequestFingerprint fingerprint,
            boolean legacyKey
    );

    QuizGenerationOperation lockForGeneration(UUID operationId, UUID userId);

    QuizGenerationOperation get(UUID operationId, UUID userId);

    SourceOperationState acquireSourceProcessing(UUID operationId, UUID userId);

    void attachSourceDocument(UUID operationId, UUID userId, UUID documentId);

    void markSourceRetryable(UUID operationId, UUID userId);

    void linkStartedGeneration(
            QuizGenerationOperation operation,
            UUID jobId,
            UUID reservationId,
            int estimatedTimeSeconds
    );

    int purgeExpiredOperations();

    enum SourceOperationState {
        REPLAY,
        READY_TO_START,
        PROCESS_SOURCE
    }
}
