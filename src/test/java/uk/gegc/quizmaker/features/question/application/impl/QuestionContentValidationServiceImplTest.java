package uk.gegc.quizmaker.features.question.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Question content validation service")
class QuestionContentValidationServiceImplTest {

    @Mock
    private QuestionHandlerFactory questionHandlerFactory;

    @Mock
    private QuestionHandler questionHandler;

    private QuestionContentValidationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new QuestionContentValidationServiceImpl(questionHandlerFactory);
    }

    @Test
    @DisplayName("Delegates content to the authoritative handler for its question type")
    void validateContentDelegatesToQuestionHandler() throws Exception {
        JsonNode content = new ObjectMapper().readTree("""
                {"answer": true}
                """);
        when(questionHandlerFactory.getHandler(QuestionType.TRUE_FALSE)).thenReturn(questionHandler);

        service.validateContent(QuestionType.TRUE_FALSE, content);

        ArgumentCaptor<QuestionContentRequest> requestCaptor =
                ArgumentCaptor.forClass(QuestionContentRequest.class);
        verify(questionHandler).validateContent(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getType()).isEqualTo(QuestionType.TRUE_FALSE);
        assertThat(requestCaptor.getValue().getContent()).isSameAs(content);
    }

    @Test
    @DisplayName("Rejects a missing question type before handler lookup")
    void validateContentRejectsMissingQuestionType() {
        assertThatThrownBy(() -> service.validateContent(null, new ObjectMapper().createObjectNode()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Question type is required");

        verifyNoInteractions(questionHandlerFactory);
    }

    @Test
    @DisplayName("Propagates missing handler failures so callers fail closed")
    void validateContentPropagatesMissingHandlerFailure() {
        when(questionHandlerFactory.getHandler(QuestionType.OPEN))
                .thenThrow(new UnsupportedOperationException("No handler for type OPEN"));

        assertThatThrownBy(() -> service.validateContent(
                QuestionType.OPEN,
                new ObjectMapper().createObjectNode()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("No handler for type OPEN");
    }
}
