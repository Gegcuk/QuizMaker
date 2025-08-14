package uk.gegc.quizmaker.service.question.factory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("QuestionHandlerFactory Integration Tests")
class QuestionHandlerFactoryIntegrationTest {

    @Autowired
    private QuestionHandlerFactory questionHandlerFactory;

    @Test
    @DisplayName("should automatically discover and register all question handlers")
    void shouldAutomaticallyDiscoverAndRegisterAllQuestionHandlers() {
        // Test that all question types have handlers
        for (QuestionType type : QuestionType.values()) {
            QuestionHandler handler = questionHandlerFactory.getHandler(type);
            assertThat(handler).isNotNull();
            assertThat(handler.supportedType()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("should return correct handler for MCQ_SINGLE type")
    void shouldReturnCorrectHandlerForMcqSingleType() {
        QuestionHandler handler = questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE);
        assertThat(handler).isNotNull();
        assertThat(handler.supportedType()).isEqualTo(QuestionType.MCQ_SINGLE);
    }

    @Test
    @DisplayName("should return correct handler for TRUE_FALSE type")
    void shouldReturnCorrectHandlerForTrueFalseType() {
        QuestionHandler handler = questionHandlerFactory.getHandler(QuestionType.TRUE_FALSE);
        assertThat(handler).isNotNull();
        assertThat(handler.supportedType()).isEqualTo(QuestionType.TRUE_FALSE);
    }

    @Test
    @DisplayName("should return correct handler for OPEN type")
    void shouldReturnCorrectHandlerForOpenType() {
        QuestionHandler handler = questionHandlerFactory.getHandler(QuestionType.OPEN);
        assertThat(handler).isNotNull();
        assertThat(handler.supportedType()).isEqualTo(QuestionType.OPEN);
    }

    @Test
    @DisplayName("should return correct handler for FILL_GAP type")
    void shouldReturnCorrectHandlerForFillGapType() {
        QuestionHandler handler = questionHandlerFactory.getHandler(QuestionType.FILL_GAP);
        assertThat(handler).isNotNull();
        assertThat(handler.supportedType()).isEqualTo(QuestionType.FILL_GAP);
    }

    @Test
    @DisplayName("should return correct handler for COMPLIANCE type")
    void shouldReturnCorrectHandlerForComplianceType() {
        QuestionHandler handler = questionHandlerFactory.getHandler(QuestionType.COMPLIANCE);
        assertThat(handler).isNotNull();
        assertThat(handler.supportedType()).isEqualTo(QuestionType.COMPLIANCE);
    }

    @Test
    @DisplayName("should return correct handler for HOTSPOT type")
    void shouldReturnCorrectHandlerForHotspotType() {
        QuestionHandler handler = questionHandlerFactory.getHandler(QuestionType.HOTSPOT);
        assertThat(handler).isNotNull();
        assertThat(handler.supportedType()).isEqualTo(QuestionType.HOTSPOT);
    }

    @Test
    @DisplayName("should return correct handler for ORDERING type")
    void shouldReturnCorrectHandlerForOrderingType() {
        QuestionHandler handler = questionHandlerFactory.getHandler(QuestionType.ORDERING);
        assertThat(handler).isNotNull();
        assertThat(handler.supportedType()).isEqualTo(QuestionType.ORDERING);
    }

    @Test
    @DisplayName("should return correct handler for MCQ_MULTI type")
    void shouldReturnCorrectHandlerForMcqMultiType() {
        QuestionHandler handler = questionHandlerFactory.getHandler(QuestionType.MCQ_MULTI);
        assertThat(handler).isNotNull();
        assertThat(handler.supportedType()).isEqualTo(QuestionType.MCQ_MULTI);
    }
} 