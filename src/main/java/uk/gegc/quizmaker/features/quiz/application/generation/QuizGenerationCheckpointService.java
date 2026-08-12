package uk.gegc.quizmaker.features.quiz.application.generation;

import uk.gegc.quizmaker.features.question.domain.model.Question;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface QuizGenerationCheckpointService {

    void save(UUID jobId, Map<Integer, List<Question>> chunkQuestions);

    GeneratedQuizCheckpoint getRequired(UUID jobId);

    boolean exists(UUID jobId);

    int delete(UUID jobId);

    RecoveryBatch findRecoveryBatch(int recoveryGraceSeconds, int batchSize);

    record RecoveryBatch(
            List<UUID> checkpointedNotStarted,
            List<UUID> checkpointedFinalizing,
            List<UUID> uncheckpointedFinalizing,
            List<UUID> expiredUncheckpointed
    ) {
        public RecoveryBatch {
            checkpointedNotStarted = List.copyOf(checkpointedNotStarted);
            checkpointedFinalizing = List.copyOf(checkpointedFinalizing);
            uncheckpointedFinalizing = List.copyOf(uncheckpointedFinalizing);
            expiredUncheckpointed = List.copyOf(expiredUncheckpointed);
        }
    }
}
