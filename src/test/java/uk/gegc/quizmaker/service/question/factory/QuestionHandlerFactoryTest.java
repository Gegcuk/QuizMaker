package uk.gegc.quizmaker.service.question.factory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.service.question.handler.QuestionHandler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("QuestionHandlerFactory Tests")
class QuestionHandlerFactoryTest {

    @Mock
    private QuestionHandler mcqSingleHandler;
    
    @Mock
    private QuestionHandler trueFalseHandler;
    
    @Mock
    private QuestionHandler openQuestionHandler;

    private QuestionHandlerFactory factory;

    @BeforeEach
    void setUp() {
        when(mcqSingleHandler.supportedType()).thenReturn(QuestionType.MCQ_SINGLE);
        when(trueFalseHandler.supportedType()).thenReturn(QuestionType.TRUE_FALSE);
        when(openQuestionHandler.supportedType()).thenReturn(QuestionType.OPEN);

        factory = new QuestionHandlerFactory(List.of(mcqSingleHandler, trueFalseHandler, openQuestionHandler));
    }

    @Test
    @DisplayName("should return correct handler for MCQ_SINGLE type")
    void shouldReturnCorrectHandlerForMcqSingleType() {
        QuestionHandler handler = factory.getHandler(QuestionType.MCQ_SINGLE);
        assertThat(handler).isEqualTo(mcqSingleHandler);
    }

    @Test
    @DisplayName("should return correct handler for TRUE_FALSE type")
    void shouldReturnCorrectHandlerForTrueFalseType() {
        QuestionHandler handler = factory.getHandler(QuestionType.TRUE_FALSE);
        assertThat(handler).isEqualTo(trueFalseHandler);
    }

    @Test
    @DisplayName("should return correct handler for OPEN type")
    void shouldReturnCorrectHandlerForOpenType() {
        QuestionHandler handler = factory.getHandler(QuestionType.OPEN);
        assertThat(handler).isEqualTo(openQuestionHandler);
    }

    @Test
    @DisplayName("should throw exception for unsupported type")
    void shouldThrowExceptionForUnsupportedType() {
        assertThatThrownBy(() -> factory.getHandler(QuestionType.HOTSPOT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("No handler for type HOTSPOT");
    }

    @Test
    @DisplayName("should handle duplicate handler types by using last one")
    void shouldHandleDuplicateHandlerTypesByUsingLastOne() {
        QuestionHandler duplicateHandler = mock(QuestionHandler.class);
        when(duplicateHandler.supportedType()).thenReturn(QuestionType.MCQ_SINGLE);

        QuestionHandlerFactory factoryWithDuplicates = new QuestionHandlerFactory(
                List.of(mcqSingleHandler, duplicateHandler)
        );

        QuestionHandler handler = factoryWithDuplicates.getHandler(QuestionType.MCQ_SINGLE);
        assertThat(handler).isEqualTo(duplicateHandler);
    }

    @Test
    @DisplayName("should work with empty handler list")
    void shouldWorkWithEmptyHandlerList() {
        QuestionHandlerFactory emptyFactory = new QuestionHandlerFactory(List.of());
        
        assertThatThrownBy(() -> emptyFactory.getHandler(QuestionType.MCQ_SINGLE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("No handler for type MCQ_SINGLE");
    }
} 