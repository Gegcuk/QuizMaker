package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerReviewDto;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest;
import uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentAvailability;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.mapping.QuestionMapper;
import uk.gegc.quizmaker.features.question.infra.mapping.SafeQuestionMapper;
import uk.gegc.quizmaker.features.question.infra.mapping.QuestionMediaResolver;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestionContractAvailabilityTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void damagedQuestionDoesNotHideHealthySiblingsAndNeverBecomesAnEmptyValidQuestion() {
        MediaAssetService media = mock(MediaAssetService.class);
        var mapper = new SafeQuestionMapper(QuestionContractTestSupport.safeContentBuilder(), new QuestionMediaResolver(media));
        Question good = question(QuestionType.TRUE_FALSE, "{\"answer\":true}");
        Question bad = question(QuestionType.MCQ_SINGLE, "{\"options\":null,\"private-canary\":\"secret-answer\"}");
        var result = mapper.toSafeDtoList(List.of(good, bad));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
        assertThat(result.get(0).getSafeContent().isObject()).isTrue();
        assertThat(result.get(1).getId()).isEqualTo(bad.getId());
        assertThat(result.get(1).getContentAvailability()).isEqualTo(QuestionContentAvailability.UNAVAILABLE);
        assertThat(result.get(1).getSafeContent()).isNull();
        assertThat(result.get(1).getContentProblem().getStatus()).isEqualTo(409);
        assertThat(json.valueToTree(result).toString()).doesNotContain("private-canary", "secret-answer");
        var author = QuestionMapper.toDto(bad);
        assertThat(author.getContentAvailability()).isEqualTo(QuestionContentAvailability.UNAVAILABLE);
        assertThat(author.getContent()).isNull();
        verifyNoInteractions(media);
    }

    @ParameterizedTest
    @EnumSource(value = QuestionType.class, names = {"MCQ_SINGLE", "MCQ_MULTI", "OPEN", "FILL_GAP", "HOTSPOT", "COMPLIANCE", "MATCHING"})
    void unanswerableStoredQuestionsAreUnavailableWithoutHidingSiblings(QuestionType type) {
        var mapper = new SafeQuestionMapper(QuestionContractTestSupport.safeContentBuilder(), new QuestionMediaResolver(mock(MediaAssetService.class)));
        var good = question(QuestionType.TRUE_FALSE, "{\"answer\":true}");
        var bad = question(type, QuestionContractTestSupport.structurallyValidButUnanswerable(type).toString());
        var result = mapper.toSafeDtoList(List.of(good, bad));
        assertThat(result.get(0).getContentAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
        assertThat(result.get(1).getContentAvailability()).isEqualTo(QuestionContentAvailability.UNAVAILABLE);
        assertThat(result.get(1).getSafeContent()).isNull();
        assertThat(result.get(1).getContentProblem().getStatus()).isEqualTo(409);
    }

    @ParameterizedTest
    @EnumSource(QuestionType.class)
    void goldenReviewsRoundTripIncludingExplicitAvailability(QuestionType type) throws Exception {
        var fixture = QuestionContracts.reviewExample(type);
        var review = json.treeToValue(fixture, AnswerReviewDto.class);
        assertThat(review.type()).isEqualTo(type);
        assertThat(review.correctAnswerAvailability()).isEqualTo(QuestionContentAvailability.AVAILABLE);
        var written = json.readTree(json.writeValueAsBytes(review));
        assertThat(written.path("contractVersion").asInt()).isEqualTo(1);
        assertThat(written.path("correctAnswer")).isEqualTo(fixture.path("correctAnswer"));
        assertThat(written.path("isCorrect").asBoolean()).isTrue();
    }

    @Test
    void requestVersionsAreOptionalButExplicitUnknownNullAndWrongTypeVersionsFail() throws Exception {
        String response = "{\"questionId\":\"00000000-0000-0000-0000-000000000001\",\"response\":{\"answer\":true}";
        for (Class<?> type : List.of(AnswerSubmissionRequest.class, CreateQuestionRequest.class, UpdateQuestionRequest.class)) {
            String base = type == AnswerSubmissionRequest.class ? response : "{\"type\":\"TRUE_FALSE\",\"content\":{\"answer\":true}";
            assertThat(json.readValue(base + ",\"clientExtension\":true}", type)).isNotNull();
            assertThat(json.readValue(base + ",\"contractVersion\":1}", type)).isNotNull();
            for (String version : List.of("2", "null", "\"1\"", "1.5")) {
                assertThatThrownBy(() -> json.readValue(base + ",\"contractVersion\":" + version + "}", type))
                        .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
            }
        }
    }

    @Test
    void omittedReviewSolutionIsDistinctFromAnIncorrectAnswer() throws Exception {
        var response = json.createObjectNode().put("answer", false);
        var review = new AnswerReviewDto(UUID.randomUUID(), QuestionType.TRUE_FALSE, "Example question",
                null, null, null, null, response, null, false, 0.0, java.time.Instant.parse("2026-10-19T12:00:00Z"));
        var wire = json.readTree(json.writeValueAsBytes(review));
        assertThat(wire.path("correctAnswerAvailability").asText()).isEqualTo("NOT_INCLUDED");
        assertThat(wire.has("correctAnswer")).isFalse();
        assertThat(wire.path("isCorrect").isBoolean()).isTrue();
        assertThat(wire.path("isCorrect").booleanValue()).isFalse();
        assertThat(wire.path("score").doubleValue()).isZero();
    }

    private Question question(QuestionType type, String content) {
        Question question = new Question();
        question.setId(UUID.randomUUID()); question.setType(type); question.setQuestionText("Example question");
        question.setContent(content);
        return question;
    }
}
