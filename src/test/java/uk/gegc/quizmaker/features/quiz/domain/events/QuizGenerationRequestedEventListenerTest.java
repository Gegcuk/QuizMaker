package uk.gegc.quizmaker.features.quiz.domain.events;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class QuizGenerationRequestedEventListenerTest {
    @Test void saturatedManagedDispatchKeepsThePublisherOutOfGeneration() {
        var ai = mock(AiQuizGenerationService.class);
        var work = mock(GenerationWorkService.class);
        var dispatcher = mock(GenerationWorkDispatcher.class);
        var legacyExecutor = mock(Executor.class);
        UUID id = UUID.randomUUID();
        when(work.isManaged(id)).thenReturn(true);
        when(dispatcher.dispatch()).thenReturn(GenerationWorkDispatcher.Admission.DEFERRED);
        new QuizGenerationRequestedEventListener(ai, work, dispatcher, legacyExecutor)
                .handleQuizGenerationRequest(new QuizGenerationRequestedEvent(this, id, null));
        verify(dispatcher).dispatch();
        verifyNoInteractions(ai, legacyExecutor);
    }

    @Test void rejectedLegacyDispatchNeverFallsBackToThePublisherThread() {
        var ai = mock(AiQuizGenerationService.class);
        var work = mock(GenerationWorkService.class);
        var dispatcher = mock(GenerationWorkDispatcher.class);
        Executor saturated = task -> { throw new RejectedExecutionException(); };
        var listener = new QuizGenerationRequestedEventListener(ai, work, dispatcher, saturated);
        assertThatCode(() -> listener.handleQuizGenerationRequest(
                new QuizGenerationRequestedEvent(this, UUID.randomUUID(), null))).doesNotThrowAnyException();
        verifyNoInteractions(ai, dispatcher);
    }
}
