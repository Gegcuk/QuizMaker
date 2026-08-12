package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCheckpointService;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCheckpointedEvent;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quiz generation checkpoint event listener")
class QuizGenerationCheckpointEventListenerTest {

    @Mock
    private QuizGenerationCheckpointService checkpointService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private QuizGenerationCheckpointEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new QuizGenerationCheckpointEventListener(checkpointService, eventPublisher);
    }

    @Test
    @DisplayName("Durable checkpoint completes before finalization event publication")
    void checkpointCompletesBeforeFinalizationDispatch() {
        QuizGenerationCompletedEvent event = completedEvent();

        listener.checkpointGeneratedOutput(event);

        InOrder order = inOrder(checkpointService, eventPublisher);
        order.verify(checkpointService).save(event.getJobId(), event.getChunkQuestions());
        order.verify(eventPublisher).publishEvent(any(QuizGenerationCheckpointedEvent.class));
    }

    @Test
    @DisplayName("Checkpoint persistence failure suppresses finalization dispatch")
    void checkpointFailureSuppressesDispatch() {
        QuizGenerationCompletedEvent event = completedEvent();
        doThrow(new IllegalStateException("database unavailable"))
                .when(checkpointService).save(event.getJobId(), event.getChunkQuestions());

        assertThatThrownBy(() -> listener.checkpointGeneratedOutput(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Dispatch failure is contained after checkpoint so scheduled recovery can continue")
    void dispatchFailureIsContainedAfterCheckpoint() {
        QuizGenerationCompletedEvent event = completedEvent();
        doThrow(new IllegalStateException("executor unavailable"))
                .when(eventPublisher).publishEvent(any(QuizGenerationCheckpointedEvent.class));

        assertThatCode(() -> listener.checkpointGeneratedOutput(event)).doesNotThrowAnyException();

        verify(checkpointService).save(event.getJobId(), event.getChunkQuestions());
    }

    private QuizGenerationCompletedEvent completedEvent() {
        UUID jobId = UUID.randomUUID();
        Question question = new Question();
        question.setType(QuestionType.OPEN);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setQuestionText("What is durable state?");
        question.setContent("{}");
        Map<Integer, List<Question>> chunks = Map.of(0, List.of(question));
        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(), QuizScope.ENTIRE_DOCUMENT, null, null, null,
                "Durability", null, Map.of(QuestionType.OPEN, 1), Difficulty.MEDIUM,
                1, null, List.of()
        );
        return new QuizGenerationCompletedEvent(this, jobId, chunks, request, List.of(question));
    }
}
