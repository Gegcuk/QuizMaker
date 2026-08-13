package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationCoverageSnapshot;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCoverageService;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCoverageReconciledEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationCoverageOutcome;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quiz generation coverage event listener")
class QuizGenerationCoverageEventListenerTest {

    @Mock
    private QuizGenerationCoverageService coverageService;

    @Test
    @DisplayName("Synchronous reconciliation event persists the exact authoritative snapshot")
    void eventPersistsExactSnapshot() {
        QuizGenerationCoverageEventListener listener =
                new QuizGenerationCoverageEventListener(coverageService);
        UUID jobId = UUID.randomUUID();
        GenerationCoverageSnapshot snapshot = new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.COMPLETE,
                80,
                1,
                1,
                0,
                0,
                List.of(new GenerationCoverageSnapshot.TypeCoverage(
                        QuestionType.MCQ_SINGLE, 1, 1, 0))
        );

        listener.persistCoverage(new QuizGenerationCoverageReconciledEvent(this, jobId, snapshot));

        InOrder order = inOrder(coverageService);
        order.verify(coverageService).saveDecision(jobId, snapshot);
    }
}
