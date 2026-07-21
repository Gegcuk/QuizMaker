package uk.gegc.quizmaker.features.quiz.api.dto;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuizGenerationStatusTest {

    @Test
    void fromEntity_exposesBoundedProgressWithAtMostTwoDecimalPlaces() {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setStatus(GenerationStatus.PROCESSING);
        job.setProgressPercentage(42.812831723);

        QuizGenerationStatus status = QuizGenerationStatus.fromEntity(job);

        assertEquals(42.81, status.progressPercentage());
    }
}
