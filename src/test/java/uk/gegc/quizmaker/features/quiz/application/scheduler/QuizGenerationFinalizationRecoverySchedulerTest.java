package uk.gegc.quizmaker.features.quiz.application.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quiz generation finalization recovery scheduler")
class QuizGenerationFinalizationRecoverySchedulerTest {

    @Mock
    private QuizGenerationFacade quizGenerationFacade;

    @Test
    @DisplayName("Delegates the recovery scan to the generation facade")
    void recoverStalledFinalizations_delegatesToFacade() {
        when(quizGenerationFacade.recoverStalledQuizGenerationFinalizations()).thenReturn(2);
        QuizGenerationFinalizationRecoveryScheduler scheduler =
                new QuizGenerationFinalizationRecoveryScheduler(quizGenerationFacade);

        scheduler.recoverStalledFinalizations();

        verify(quizGenerationFacade).recoverStalledQuizGenerationFinalizations();
    }

    @Test
    @DisplayName("Scheduler contains a recovery failure so later scheduled runs continue")
    void recoverStalledFinalizations_containsFailure() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(quizGenerationFacade).recoverStalledQuizGenerationFinalizations();
        QuizGenerationFinalizationRecoveryScheduler scheduler =
                new QuizGenerationFinalizationRecoveryScheduler(quizGenerationFacade);

        assertThatCode(scheduler::recoverStalledFinalizations).doesNotThrowAnyException();

        verify(quizGenerationFacade).recoverStalledQuizGenerationFinalizations();
    }
}
