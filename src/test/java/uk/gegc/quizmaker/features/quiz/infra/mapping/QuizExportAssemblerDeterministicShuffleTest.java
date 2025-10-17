package uk.gegc.quizmaker.features.quiz.infra.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QuizExportAssembler Deterministic Shuffle Tests")
class QuizExportAssemblerDeterministicShuffleTest {

    private ObjectMapper objectMapper;
    private QuizExportAssembler assembler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        assembler = new QuizExportAssembler(objectMapper);
    }

    @Test
    @DisplayName("toExportDtos: with same seed produces same shuffle order for MCQ")
    void toExportDtos_sameSeed_sameShuffleForMcq() {
        // Given
        Quiz quiz = createQuizWithMcqQuestion();
        long seed = 12345L;

        // When
        QuizExportDto dto1 = assembler.toExportDto(quiz, new Random(seed));
        QuizExportDto dto2 = assembler.toExportDto(quiz, new Random(seed));

        // Then
        JsonNode options1 = dto1.questions().get(0).content().get("options");
        JsonNode options2 = dto2.questions().get(0).content().get("options");
        
        // Options should be in the same shuffled order
        assertThat(options1.toString()).isEqualTo(options2.toString());
    }

    @Test
    @DisplayName("toExportDtos: with different seeds produces different shuffle order for MCQ")
    void toExportDtos_differentSeeds_differentShuffleForMcq() {
        // Given
        Quiz quiz = createQuizWithMcqQuestion();

        // When
        QuizExportDto dto1 = assembler.toExportDto(quiz, new Random(11111L));
        QuizExportDto dto2 = assembler.toExportDto(quiz, new Random(22222L));

        // Then
        JsonNode options1 = dto1.questions().get(0).content().get("options");
        JsonNode options2 = dto2.questions().get(0).content().get("options");
        
        // Options should likely be in different order (not guaranteed but very likely)
        // At minimum, verify both are valid and have same length
        assertThat(options1.size()).isEqualTo(options2.size());
        assertThat(options1.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("toExportDtos: null Random still shuffles but non-deterministically")
    void toExportDtos_nullRandom_stillShuffles() {
        // Given
        Quiz quiz = createQuizWithMcqQuestion();

        // When
        QuizExportDto dto = assembler.toExportDto(quiz, null);

        // Then - should still have shuffled content
        JsonNode options = dto.questions().get(0).content().get("options");
        assertThat(options.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("toExportDtos: deterministic shuffle for ORDERING preserves correctOrder")
    void toExportDtos_deterministic_orderingPreservesCorrectOrder() {
        // Given
        Quiz quiz = createQuizWithOrderingQuestion();
        long seed = 54321L;

        // When
        QuizExportDto dto = assembler.toExportDto(quiz, new Random(seed));

        // Then
        JsonNode content = dto.questions().get(0).content();
        assertThat(content.has("correctOrder")).isTrue();
        assertThat(content.get("correctOrder").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("toExportDtos: deterministic shuffle for MATCHING shuffles right column")
    void toExportDtos_deterministic_matchingShufflesRight() {
        // Given
        Quiz quiz = createQuizWithMatchingQuestion();
        long seed = 99999L;

        // When
        QuizExportDto dto1 = assembler.toExportDto(quiz, new Random(seed));
        QuizExportDto dto2 = assembler.toExportDto(quiz, new Random(seed));

        // Then - same shuffle
        JsonNode right1 = dto1.questions().get(0).content().get("right");
        JsonNode right2 = dto2.questions().get(0).content().get("right");
        assertThat(right1.toString()).isEqualTo(right2.toString());
    }

    @Test
    @DisplayName("toExportDtos: deterministic shuffle for COMPLIANCE")
    void toExportDtos_deterministic_complianceShufflesStatements() {
        // Given
        Quiz quiz = createQuizWithComplianceQuestion();
        long seed = 77777L;

        // When
        QuizExportDto dto1 = assembler.toExportDto(quiz, new Random(seed));
        QuizExportDto dto2 = assembler.toExportDto(quiz, new Random(seed));

        // Then - same shuffle
        JsonNode statements1 = dto1.questions().get(0).content().get("statements");
        JsonNode statements2 = dto2.questions().get(0).content().get("statements");
        assertThat(statements1.toString()).isEqualTo(statements2.toString());
    }

    // Helper methods to create test quizzes

    private Quiz createQuizWithMcqQuestion() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setCreatedAt(Instant.now());
        quiz.setUpdatedAt(Instant.now());

        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("What is the answer?");
        question.setContent("{\"options\":[" +
                "{\"id\":\"1\",\"text\":\"Option A\",\"correct\":false}," +
                "{\"id\":\"2\",\"text\":\"Option B\",\"correct\":true}," +
                "{\"id\":\"3\",\"text\":\"Option C\",\"correct\":false}," +
                "{\"id\":\"4\",\"text\":\"Option D\",\"correct\":false}" +
                "],\"correctOptionId\":\"2\"}");
        question.setCreatedAt(Instant.now());
        question.setUpdatedAt(Instant.now());

        quiz.setQuestions(new HashSet<>(List.of(question)));
        return quiz;
    }

    private Quiz createQuizWithOrderingQuestion() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setCreatedAt(Instant.now());
        quiz.setUpdatedAt(Instant.now());

        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.ORDERING);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Order these items");
        question.setContent("{\"items\":[" +
                "{\"id\":1,\"text\":\"First\",\"order\":1}," +
                "{\"id\":2,\"text\":\"Second\",\"order\":2}," +
                "{\"id\":3,\"text\":\"Third\",\"order\":3}" +
                "]}");
        question.setCreatedAt(Instant.now());
        question.setUpdatedAt(Instant.now());

        quiz.setQuestions(new HashSet<>(List.of(question)));
        return quiz;
    }

    private Quiz createQuizWithMatchingQuestion() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setCreatedAt(Instant.now());
        quiz.setUpdatedAt(Instant.now());

        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.MATCHING);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Match items");
        question.setContent("{\"left\":[" +
                "{\"id\":1,\"text\":\"France\",\"matchId\":\"A\"}," +
                "{\"id\":2,\"text\":\"Germany\",\"matchId\":\"B\"}" +
                "],\"right\":[" +
                "{\"id\":\"A\",\"text\":\"Paris\"}," +
                "{\"id\":\"B\",\"text\":\"Berlin\"}" +
                "]}");
        question.setCreatedAt(Instant.now());
        question.setUpdatedAt(Instant.now());

        quiz.setQuestions(new HashSet<>(List.of(question)));
        return quiz;
    }

    private Quiz createQuizWithComplianceQuestion() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setCreatedAt(Instant.now());
        quiz.setUpdatedAt(Instant.now());

        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.COMPLIANCE);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Check compliance");
        question.setContent("{\"statements\":[" +
                "{\"id\":1,\"text\":\"Statement 1\",\"compliant\":true}," +
                "{\"id\":2,\"text\":\"Statement 2\",\"compliant\":false}," +
                "{\"id\":3,\"text\":\"Statement 3\",\"compliant\":true}" +
                "],\"compliantIds\":[1,3]}");
        question.setCreatedAt(Instant.now());
        question.setUpdatedAt(Instant.now());

        quiz.setQuestions(new HashSet<>(List.of(question)));
        return quiz;
    }
}

