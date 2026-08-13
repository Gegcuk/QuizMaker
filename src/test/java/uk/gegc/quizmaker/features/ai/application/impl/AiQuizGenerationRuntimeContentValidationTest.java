package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.question.application.QuestionContentShuffler;
import uk.gegc.quizmaker.features.question.application.QuestionContentValidationService;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContentValidationServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.testing.DirectAiProviderTaskScheduler;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.ComplianceHandler;
import uk.gegc.quizmaker.features.question.infra.handler.FillGapHandler;
import uk.gegc.quizmaker.features.question.infra.handler.HotspotHandler;
import uk.gegc.quizmaker.features.question.infra.handler.MatchingHandler;
import uk.gegc.quizmaker.features.question.infra.handler.McqMultiHandler;
import uk.gegc.quizmaker.features.question.infra.handler.McqSingleHandler;
import uk.gegc.quizmaker.features.question.infra.handler.OpenQuestionHandler;
import uk.gegc.quizmaker.features.question.infra.handler.OrderingHandler;
import uk.gegc.quizmaker.features.question.infra.handler.TrueFalseHandler;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("AI question runtime content validation")
class AiQuizGenerationRuntimeContentValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private QuestionContentValidationService validationService;
    private AiQuizGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        QuestionHandlerFactory handlerFactory = new QuestionHandlerFactory(List.of(
                new McqSingleHandler(),
                new McqMultiHandler(),
                new TrueFalseHandler(),
                new OpenQuestionHandler(),
                new FillGapHandler(),
                new OrderingHandler(),
                new ComplianceHandler(),
                new HotspotHandler(),
                new MatchingHandler()
        ));
        validationService = new QuestionContentValidationServiceImpl(handlerFactory);
        service = createService(
                new QuestionContentShuffler(objectMapper),
                validationService
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runtimeValidContent")
    @DisplayName("Accepts content that every supported runtime handler can consume")
    void convertStructuredQuestionsAcceptsEveryRuntimeValidType(
            String scenario,
            QuestionType type,
            String content) throws Exception {
        StructuredQuestion generated = structuredQuestion(scenario, type, content);

        List<Question> result = service.convertStructuredQuestions(List.of(generated));

        assertThat(result).singleElement().satisfies(question -> {
            assertThat(question.getQuestionText()).isEqualTo(scenario);
            assertThat(question.getType()).isEqualTo(type);
            assertThat(question.getDifficulty()).isEqualTo(Difficulty.MEDIUM);
            assertThat(question.getHint()).isEqualTo("Hint");
            assertThat(question.getExplanation()).isEqualTo("Explanation");
            assertThatCode(() -> validationService.validateContent(
                    type,
                    objectMapper.readTree(question.getContent())))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("Preserves both legacy typed and distractor-based fill-gap content")
    void convertStructuredQuestionsAcceptsBothFillGapFormats() throws Exception {
        StructuredQuestion legacy = structuredQuestion(
                "Legacy fill gap",
                QuestionType.FILL_GAP,
                """
                        {
                          "text": "The capital of France is {1}.",
                          "gaps": [{"id": 1, "answer": "Paris"}]
                        }
                        """
        );
        StructuredQuestion withDistractors = structuredQuestion(
                "Fill gap with distractors",
                QuestionType.FILL_GAP,
                """
                        {
                          "text": "The capital of France is {1}.",
                          "gaps": [{"id": 1, "answer": "Paris"}],
                          "options": ["Paris", "London", "Berlin", "Madrid", "Rome", "Lisbon", "Vienna"]
                        }
                        """
        );

        List<Question> result = service.convertStructuredQuestions(List.of(legacy, withDistractors));

        assertThat(result).extracting(Question::getQuestionText)
                .containsExactly("Legacy fill gap", "Fill gap with distractors");
        JsonNode legacyContent = objectMapper.readTree(result.get(0).getContent());
        JsonNode distractorContent = objectMapper.readTree(result.get(1).getContent());
        assertThat(legacyContent.has("options")).isFalse();
        assertThat(distractorContent.get("options")).hasSize(7);
    }

    @Test
    @DisplayName("Rejects fill-gap options that omit the correct answer")
    void convertStructuredQuestionsRejectsInvalidFillGapOptions() {
        StructuredQuestion invalidOptions = structuredQuestion(
                "Fill gap missing its answer option",
                QuestionType.FILL_GAP,
                """
                        {
                          "text": "The capital of France is {1}.",
                          "gaps": [{"id": 1, "answer": "Paris"}],
                          "options": ["London", "Berlin", "Madrid", "Rome", "Lisbon", "Vienna", "Prague"]
                        }
                        """
        );

        List<Question> result = service.convertStructuredQuestions(List.of(invalidOptions));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Drops malformed entries without discarding valid questions or changing their order")
    void convertStructuredQuestionsDropsInvalidEntriesAndPreservesValidOrder() {
        StructuredQuestion firstValid = structuredQuestion(
                "First valid",
                QuestionType.TRUE_FALSE,
                "{\"answer\": true}"
        );
        StructuredQuestion brokenMatching = structuredQuestion(
                "Broken matching",
                QuestionType.MATCHING,
                """
                        {
                          "left": [
                            {"id": 1, "text": "A", "matchId": 10},
                            {"id": 2, "text": "B", "matchId": 999}
                          ],
                          "right": [
                            {"id": 10, "text": "One"},
                            {"id": 11, "text": "Two"}
                          ]
                        }
                        """
        );
        StructuredQuestion lastValid = structuredQuestion(
                "Last valid",
                QuestionType.OPEN,
                "{\"answer\": \"Paris\"}"
        );

        List<Question> result = service.convertStructuredQuestions(
                List.of(firstValid, brokenMatching, lastValid));

        assertThat(result).extracting(Question::getQuestionText)
                .containsExactly("First valid", "Last valid");
    }

    @Test
    @DisplayName("Returns no domain questions when every generated entry is malformed")
    void convertStructuredQuestionsReturnsEmptyWhenEveryEntryIsInvalid() {
        StructuredQuestion malformedJson = structuredQuestion(
                "Malformed JSON",
                QuestionType.TRUE_FALSE,
                "{not-json"
        );
        StructuredQuestion blankContent = structuredQuestion(
                "Blank content",
                QuestionType.OPEN,
                "   "
        );
        StructuredQuestion missingType = structuredQuestion(
                "Missing type",
                null,
                "{\"answer\": true}"
        );

        List<Question> result = service.convertStructuredQuestions(
                java.util.Arrays.asList(null, malformedJson, blankContent, missingType));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Validates content before invoking the shuffler")
    void convertStructuredQuestionsValidatesBeforeShuffling() throws Exception {
        QuestionContentValidationService rejectingValidator = mock(QuestionContentValidationService.class);
        QuestionContentShuffler shuffler = mock(QuestionContentShuffler.class);
        AiQuizGenerationServiceImpl validatingService = createService(shuffler, rejectingValidator);
        doThrow(new IllegalStateException("validator unavailable"))
                .when(rejectingValidator)
                .validateContent(eq(QuestionType.ORDERING), any(JsonNode.class));
        StructuredQuestion invalidOrdering = structuredQuestion(
                "Invalid ordering",
                QuestionType.ORDERING,
                """
                        {
                          "items": [
                            {"id": 1, "text": "First"},
                            {"id": 1, "text": "Second"}
                          ]
                        }
                        """
        );

        List<Question> result = validatingService.convertStructuredQuestions(List.of(invalidOrdering));

        assertThat(result).isEmpty();
        verifyNoInteractions(shuffler);
    }

    private AiQuizGenerationServiceImpl createService(
            QuestionContentShuffler shuffler,
            QuestionContentValidationService validator) {
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
                null,
                shuffler,
                validator,
                null,
                DirectAiProviderTaskScheduler.INSTANCE
        );
    }

    private static StructuredQuestion structuredQuestion(
            String questionText,
            QuestionType type,
            String content) {
        return StructuredQuestion.builder()
                .questionText(questionText)
                .type(type)
                .difficulty(Difficulty.MEDIUM)
                .content(content)
                .hint("Hint")
                .explanation("Explanation")
                .build();
    }

    private static Stream<Arguments> runtimeValidContent() {
        return Stream.of(
                Arguments.of(
                        "MCQ single",
                        QuestionType.MCQ_SINGLE,
                        """
                                {
                                  "options": [
                                    {"id": "a", "text": "Paris", "correct": true},
                                    {"id": "b", "text": "London", "correct": false}
                                  ]
                                }
                                """
                ),
                Arguments.of(
                        "MCQ multi",
                        QuestionType.MCQ_MULTI,
                        """
                                {
                                  "options": [
                                    {"id": "a", "text": "Paris", "correct": true},
                                    {"id": "b", "text": "Lyon", "correct": true},
                                    {"id": "c", "text": "London", "correct": false}
                                  ]
                                }
                                """
                ),
                Arguments.of("True or false", QuestionType.TRUE_FALSE, "{\"answer\": true}"),
                Arguments.of("Open", QuestionType.OPEN, "{\"answer\": \"Paris\"}"),
                Arguments.of(
                        "Legacy fill gap",
                        QuestionType.FILL_GAP,
                        """
                                {
                                  "text": "The capital of France is {1}.",
                                  "gaps": [{"id": 1, "answer": "Paris"}]
                                }
                                """
                ),
                Arguments.of(
                        "Matching",
                        QuestionType.MATCHING,
                        """
                                {
                                  "left": [
                                    {"id": 1, "text": "France", "matchId": 10},
                                    {"id": 2, "text": "Italy", "matchId": 11}
                                  ],
                                  "right": [
                                    {"id": 10, "text": "Paris"},
                                    {"id": 11, "text": "Rome"}
                                  ]
                                }
                                """
                ),
                Arguments.of(
                        "Ordering",
                        QuestionType.ORDERING,
                        """
                                {
                                  "items": [
                                    {"id": 1, "text": "First"},
                                    {"id": 2, "text": "Second"}
                                  ]
                                }
                                """
                ),
                Arguments.of(
                        "Compliance",
                        QuestionType.COMPLIANCE,
                        """
                                {
                                  "statements": [
                                    {"id": 1, "text": "Compliant", "compliant": true},
                                    {"id": 2, "text": "Not compliant", "compliant": false}
                                  ]
                                }
                                """
                ),
                Arguments.of(
                        "Hotspot",
                        QuestionType.HOTSPOT,
                        """
                                {
                                  "imageUrl": "https://example.test/map.png",
                                  "regions": [
                                    {"id": 1, "x": 0, "y": 0, "width": 20, "height": 20, "correct": true},
                                    {"id": 2, "x": 20, "y": 20, "width": 20, "height": 20, "correct": false}
                                  ]
                                }
                                """
                )
        );
    }
}
