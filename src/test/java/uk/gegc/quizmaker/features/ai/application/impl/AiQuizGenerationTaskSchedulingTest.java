package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.AiProviderCapacityException;
import uk.gegc.quizmaker.features.ai.application.AiProviderTaskScheduler;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.question.application.QuestionContentShuffler;
import uk.gegc.quizmaker.features.question.application.QuestionContentValidationService;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContentValidationServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.McqSingleHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AI chunk task scheduling")
class AiQuizGenerationTaskSchedulingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Submits one chunk task and keeps multiple questions in one structured request")
    void schedulesChunkAndPreservesQuestionBatch() {
        RecordingScheduler scheduler = new RecordingScheduler();
        StructuredAiClient structuredAiClient = mock(StructuredAiClient.class);
        when(structuredAiClient.generateQuestions(any())).thenReturn(response(3));
        AiQuizGenerationServiceImpl service = service(structuredAiClient, scheduler);

        List<Question> questions = service.generateQuestionsFromChunk(
                chunk(),
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM
        ).join();

        ArgumentCaptor<StructuredQuestionRequest> request = ArgumentCaptor.forClass(StructuredQuestionRequest.class);
        verify(structuredAiClient).generateQuestions(request.capture());
        assertThat(scheduler.submissions).hasValue(1);
        assertThat(request.getValue().getQuestionType()).isEqualTo(QuestionType.MCQ_SINGLE);
        assertThat(request.getValue().getQuestionCount()).isEqualTo(3);
        assertThat(questions).hasSize(3);
    }

    @Test
    @DisplayName("Propagates scheduler saturation without invoking the structured client")
    void rejectsChunkWithoutProviderCallWhenSchedulerIsSaturated() {
        StructuredAiClient structuredAiClient = mock(StructuredAiClient.class);
        AiProviderCapacityException capacityFailure =
                new AiProviderCapacityException(new RejectedExecutionException("full"));
        AiProviderTaskScheduler scheduler = new RejectingScheduler(capacityFailure);
        AiQuizGenerationServiceImpl service = service(structuredAiClient, scheduler);

        CompletableFuture<List<Question>> result = service.generateQuestionsFromChunk(
                chunk(),
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM
        );

        assertThatThrownBy(result::join).hasCause(capacityFailure);
        verify(structuredAiClient, never()).generateQuestions(any());
    }

    private AiQuizGenerationServiceImpl service(
            StructuredAiClient structuredAiClient,
            AiProviderTaskScheduler scheduler) {
        QuestionContentShuffler shuffler = mock(QuestionContentShuffler.class);
        when(shuffler.shuffleContent(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        QuestionContentValidationService validator = new QuestionContentValidationServiceImpl(
                new QuestionHandlerFactory(List.of(new McqSingleHandler()))
        );
        return new AiQuizGenerationServiceImpl(
                null,
                null,
                null,
                null,
                null,
                null,
                objectMapper,
                null,
                null,
                null,
                null,
                structuredAiClient,
                shuffler,
                validator,
                null,
                scheduler
        );
    }

    private DocumentChunk chunk() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkIndex(0);
        chunk.setContent("A sufficiently detailed chunk about bounded provider execution and reliable batching.");
        return chunk;
    }

    private StructuredQuestionResponse response(int count) {
        List<StructuredQuestion> questions = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> StructuredQuestion.builder()
                        .questionText("Question " + index)
                        .type(QuestionType.MCQ_SINGLE)
                        .difficulty(Difficulty.MEDIUM)
                        .content("""
                                {
                                  "options": [
                                    {"id": "a", "text": "Correct", "correct": true},
                                    {"id": "b", "text": "Incorrect", "correct": false}
                                  ]
                                }
                                """)
                        .hint("Hint")
                        .explanation("Explanation")
                        .build())
                .toList();
        return StructuredQuestionResponse.builder()
                .questions(questions)
                .build();
    }

    private static final class RecordingScheduler implements AiProviderTaskScheduler {
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            submissions.incrementAndGet();
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    private record RejectingScheduler(RuntimeException failure) implements AiProviderTaskScheduler {
        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
