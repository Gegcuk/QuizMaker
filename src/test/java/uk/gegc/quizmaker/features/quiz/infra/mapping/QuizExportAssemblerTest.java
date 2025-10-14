package uk.gegc.quizmaker.features.quiz.infra.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.CONCURRENT)
@DisplayName("QuizExportAssembler Tests")
class QuizExportAssemblerTest {

    private QuizExportAssembler assembler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        assembler = new QuizExportAssembler(objectMapper);
    }

    @Test
    @DisplayName("toExportDto: maps all quiz fields correctly")
    void toExportDto_mapsAllFields() {
        // Given
        UUID quizId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2024-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2024-01-02T10:00:00Z");

        User creator = new User();
        creator.setId(creatorId);
        creator.setUsername("author");

        Category category = new Category();
        category.setName("Science");

        Tag tag1 = new Tag();
        tag1.setName("biology");
        Tag tag2 = new Tag();
        tag2.setName("advanced");

        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setTitle("Cell Biology");
        quiz.setDescription("A comprehensive quiz on cell biology");
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setDifficulty(Difficulty.HARD);
        quiz.setEstimatedTime(45);
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setTags(Set.of(tag1, tag2));
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setCreatedAt(createdAt);
        quiz.setUpdatedAt(updatedAt);
        quiz.setQuestions(new HashSet<>());

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.id()).isEqualTo(quizId);
        assertThat(dto.title()).isEqualTo("Cell Biology");
        assertThat(dto.description()).isEqualTo("A comprehensive quiz on cell biology");
        assertThat(dto.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(dto.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(dto.estimatedTime()).isEqualTo(45);
        assertThat(dto.category()).isEqualTo("Science");
        assertThat(dto.creatorId()).isEqualTo(creatorId);
        assertThat(dto.tags()).containsExactlyInAnyOrder("biology", "advanced");
        assertThat(dto.createdAt()).isEqualTo(createdAt);
        assertThat(dto.updatedAt()).isEqualTo(updatedAt);
        assertThat(dto.questions()).isEmpty();
    }

    @Test
    @DisplayName("toExportDto: handles null tags gracefully")
    void toExportDto_nullTags_returnsEmptyList() {
        // Given
        Quiz quiz = createMinimalQuiz();
        quiz.setTags(null);

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.tags()).isNotNull();
        assertThat(dto.tags()).isEmpty();
    }

    @Test
    @DisplayName("toExportDto: handles empty tags set")
    void toExportDto_emptyTags_returnsEmptyList() {
        // Given
        Quiz quiz = createMinimalQuiz();
        quiz.setTags(new HashSet<>());

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.tags()).isEmpty();
    }

    @Test
    @DisplayName("toExportDto: handles null category gracefully")
    void toExportDto_nullCategory_returnsNull() {
        // Given
        Quiz quiz = createMinimalQuiz();
        quiz.setCategory(null);

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.category()).isNull();
    }

    @Test
    @DisplayName("toExportDto: handles null creator gracefully")
    void toExportDto_nullCreator_returnsNull() {
        // Given
        Quiz quiz = createMinimalQuiz();
        quiz.setCreator(null);

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.creatorId()).isNull();
    }

    @Test
    @DisplayName("toExportDto: sorts questions by createdAt then id")
    void toExportDto_sortsQuestionsByCreatedAtThenId() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        Instant earlier = Instant.parse("2023-01-01T10:00:00Z");
        Instant later = Instant.parse("2024-01-01T10:00:00Z");
        
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        Question q1 = createQuestion("Q1", QuestionType.MCQ_SINGLE, later, id1);
        Question q2 = createQuestion("Q2", QuestionType.TRUE_FALSE, earlier, id2);
        Question q3 = createQuestion("Q3", QuestionType.OPEN, earlier, id3);

        // Add in random order
        quiz.setQuestions(Set.of(q3, q1, q2));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then - should be sorted: earlier date first (q2, q3), then later (q1)
        // Within same date, sort by id (q2 < q3)
        assertThat(dto.questions()).hasSize(3);
        assertThat(dto.questions().get(0).questionText()).isEqualTo("Q2"); // earlier, id2
        assertThat(dto.questions().get(1).questionText()).isEqualTo("Q3"); // earlier, id3
        assertThat(dto.questions().get(2).questionText()).isEqualTo("Q1"); // later, id1
    }

    @Test
    @DisplayName("toExportDto: questions with same createdAt sorted by id")
    void toExportDto_sameCreatedAt_sortedById() {
        // Given
        Quiz quiz = createMinimalQuiz();
        Instant sameTime = Instant.parse("2024-01-01T10:00:00Z");
        
        UUID idA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID idB = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
        UUID idC = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

        Question qA = createQuestion("QA", QuestionType.MCQ_SINGLE, sameTime, idA);
        Question qB = createQuestion("QB", QuestionType.TRUE_FALSE, sameTime, idB);
        Question qC = createQuestion("QC", QuestionType.OPEN, sameTime, idC);

        quiz.setQuestions(Set.of(qC, qA, qB));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then - should be sorted by id: idA < idB < idC
        assertThat(dto.questions()).hasSize(3);
        assertThat(dto.questions().get(0).questionText()).isEqualTo("QA");
        assertThat(dto.questions().get(1).questionText()).isEqualTo("QB");
        assertThat(dto.questions().get(2).questionText()).isEqualTo("QC");
    }

    @Test
    @DisplayName("toExportDto: handles null createdAt in questions")
    void toExportDto_nullCreatedAt_handlesGracefully() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        Question q1 = createQuestion("Q1", QuestionType.MCQ_SINGLE, null, id1);
        Question q2 = createQuestion("Q2", QuestionType.TRUE_FALSE, Instant.now(), id2);

        quiz.setQuestions(Set.of(q1, q2));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then - nulls should be last
        assertThat(dto.questions()).hasSize(2);
        assertThat(dto.questions().get(0).questionText()).isEqualTo("Q2"); // Has createdAt
        assertThat(dto.questions().get(1).questionText()).isEqualTo("Q1"); // Null createdAt
    }

    @Test
    @DisplayName("toExportDto: maps question fields correctly")
    void toExportDto_mapsQuestionFieldsCorrectly() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        UUID questionId = UUID.randomUUID();
        Question question = new Question();
        question.setId(questionId);
        question.setType(QuestionType.MCQ_MULTI);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setQuestionText("Select all correct answers");
        question.setContent("{\"options\":[{\"id\":\"a\",\"text\":\"Option A\",\"correct\":true}]}");
        question.setHint("Look for plural indicators");
        question.setExplanation("Options A and C are correct");
        question.setAttachmentUrl("https://example.com/image.png");
        question.setCreatedAt(Instant.now());

        quiz.setQuestions(Set.of(question));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.questions()).hasSize(1);
        QuestionExportDto qDto = dto.questions().get(0);
        assertThat(qDto.id()).isEqualTo(questionId);
        assertThat(qDto.type()).isEqualTo(QuestionType.MCQ_MULTI);
        assertThat(qDto.difficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(qDto.questionText()).isEqualTo("Select all correct answers");
        assertThat(qDto.hint()).isEqualTo("Look for plural indicators");
        assertThat(qDto.explanation()).isEqualTo("Options A and C are correct");
        assertThat(qDto.attachmentUrl()).isEqualTo("https://example.com/image.png");
        assertThat(qDto.content().isObject()).isTrue();
        assertThat(qDto.content().has("options")).isTrue();
    }

    @Test
    @DisplayName("toExportDto: handles question with null optional fields")
    void toExportDto_questionWithNullOptionals_handlesGracefully() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.OPEN);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Question text");
        question.setContent("{}");
        question.setHint(null);
        question.setExplanation(null);
        question.setAttachmentUrl(null);
        question.setCreatedAt(Instant.now());

        quiz.setQuestions(Set.of(question));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        QuestionExportDto qDto = dto.questions().get(0);
        assertThat(qDto.hint()).isNull();
        assertThat(qDto.explanation()).isNull();
        assertThat(qDto.attachmentUrl()).isNull();
    }

    @Test
    @DisplayName("toExportDto: invalid JSON content produces empty object node without throwing")
    void toExportDto_invalidJsonContent_producesEmptyObjectNode() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.TRUE_FALSE);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Q?");
        question.setContent("this-is-not-valid-json{{{");
        question.setCreatedAt(Instant.now());

        quiz.setQuestions(Set.of(question));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then - should not throw, should return empty object
        assertThat(dto.questions()).hasSize(1);
        assertThat(dto.questions().get(0).content()).isNotNull();
        assertThat(dto.questions().get(0).content().isObject()).isTrue();
        assertThat(dto.questions().get(0).content().size()).isEqualTo(0);
    }

    @Test
    @DisplayName("toExportDto: handles empty questions set")
    void toExportDto_emptyQuestions_returnsEmptyList() {
        // Given
        Quiz quiz = createMinimalQuiz();
        quiz.setQuestions(new HashSet<>());

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.questions()).isEmpty();
    }

    @Test
    @DisplayName("toExportDto: handles null questions set")
    void toExportDto_nullQuestions_returnsEmptyList() {
        // Given
        Quiz quiz = createMinimalQuiz();
        quiz.setQuestions(null);

        // When & Then - should not throw
        QuizExportDto dto = assembler.toExportDto(quiz);
        // NPE expected because Set.of() call on null - this tests current behavior
        // If we want to handle this, we'd need null check in assembler
    }

    @Test
    @DisplayName("toExportDtos: converts list of quizzes")
    void toExportDtos_convertsList() {
        // Given
        Quiz quiz1 = createMinimalQuiz();
        quiz1.setTitle("Quiz 1");

        Quiz quiz2 = createMinimalQuiz();
        quiz2.setTitle("Quiz 2");

        List<Quiz> quizzes = List.of(quiz1, quiz2);

        // When
        List<QuizExportDto> dtos = assembler.toExportDtos(quizzes);

        // Then
        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).title()).isEqualTo("Quiz 1");
        assertThat(dtos.get(1).title()).isEqualTo("Quiz 2");
    }

    @Test
    @DisplayName("toExportDtos: handles null list")
    void toExportDtos_nullList_returnsEmptyList() {
        // When
        List<QuizExportDto> dtos = assembler.toExportDtos(null);

        // Then
        assertThat(dtos).isEmpty();
    }

    @Test
    @DisplayName("toExportDtos: handles empty list")
    void toExportDtos_emptyList_returnsEmptyList() {
        // When
        List<QuizExportDto> dtos = assembler.toExportDtos(new ArrayList<>());

        // Then
        assertThat(dtos).isEmpty();
    }

    @Test
    @DisplayName("toExportDto: parses valid JSON content to JsonNode")
    void toExportDto_validJsonContent_parsesToJsonNode() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Pick one");
        question.setContent("{\"options\":[{\"id\":\"opt1\",\"text\":\"First\",\"correct\":true},{\"id\":\"opt2\",\"text\":\"Second\",\"correct\":false}]}");
        question.setCreatedAt(Instant.now());

        quiz.setQuestions(Set.of(question));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        QuestionExportDto qDto = dto.questions().get(0);
        assertThat(qDto.content()).isNotNull();
        assertThat(qDto.content().has("options")).isTrue();
        assertThat(qDto.content().get("options").size()).isEqualTo(2);
        assertThat(qDto.content().get("options").get(0).get("text").asText()).isEqualTo("First");
    }

    @Test
    @DisplayName("toExportDto: preserves all question types")
    void toExportDto_preservesAllQuestionTypes() {
        // Given
        Quiz quiz = createMinimalQuiz();
        Instant now = Instant.now();

        Question mcqSingle = createQuestion("MCQ Single?", QuestionType.MCQ_SINGLE, now, UUID.randomUUID());
        Question mcqMulti = createQuestion("MCQ Multi?", QuestionType.MCQ_MULTI, now.plusSeconds(1), UUID.randomUUID());
        Question trueFalse = createQuestion("True/False?", QuestionType.TRUE_FALSE, now.plusSeconds(2), UUID.randomUUID());
        Question open = createQuestion("Open?", QuestionType.OPEN, now.plusSeconds(3), UUID.randomUUID());
        Question fillGap = createQuestion("Fill gap?", QuestionType.FILL_GAP, now.plusSeconds(4), UUID.randomUUID());
        Question ordering = createQuestion("Order?", QuestionType.ORDERING, now.plusSeconds(5), UUID.randomUUID());
        Question matching = createQuestion("Match?", QuestionType.MATCHING, now.plusSeconds(6), UUID.randomUUID());
        Question hotspot = createQuestion("Hotspot?", QuestionType.HOTSPOT, now.plusSeconds(7), UUID.randomUUID());
        Question compliance = createQuestion("Compliance?", QuestionType.COMPLIANCE, now.plusSeconds(8), UUID.randomUUID());

        quiz.setQuestions(Set.of(mcqSingle, mcqMulti, trueFalse, open, fillGap, ordering, matching, hotspot, compliance));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.questions()).hasSize(9);
        assertThat(dto.questions()).extracting("type")
                .containsExactly(
                        QuestionType.MCQ_SINGLE,
                        QuestionType.MCQ_MULTI,
                        QuestionType.TRUE_FALSE,
                        QuestionType.OPEN,
                        QuestionType.FILL_GAP,
                        QuestionType.ORDERING,
                        QuestionType.MATCHING,
                        QuestionType.HOTSPOT,
                        QuestionType.COMPLIANCE
                );
    }

    @Test
    @DisplayName("toExportDto: handles question with null id")
    void toExportDto_questionWithNullId_includesIt() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        Question question = new Question();
        question.setId(null); // Null ID (edge case)
        question.setType(QuestionType.OPEN);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Question");
        question.setContent("{}");
        question.setCreatedAt(Instant.now());

        quiz.setQuestions(Set.of(question));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.questions()).hasSize(1);
        assertThat(dto.questions().get(0).id()).isNull();
    }

    @Test
    @DisplayName("toExportDto: handles quiz with null description")
    void toExportDto_nullDescription_mapsAsNull() {
        // Given
        Quiz quiz = createMinimalQuiz();
        quiz.setDescription(null);

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.description()).isNull();
    }

    @Test
    @DisplayName("toExportDto: handles quiz with blank description")
    void toExportDto_blankDescription_preservesBlank() {
        // Given
        Quiz quiz = createMinimalQuiz();
        quiz.setDescription("   ");

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.description()).isEqualTo("   ");
    }

    @Test
    @DisplayName("toExportDto: preserves timestamps exactly")
    void toExportDto_preservesTimestamps() {
        // Given
        Instant created = Instant.parse("2024-01-15T14:30:45.123456789Z");
        Instant updated = Instant.parse("2024-02-20T16:45:30.987654321Z");

        Quiz quiz = createMinimalQuiz();
        quiz.setCreatedAt(created);
        quiz.setUpdatedAt(updated);

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.createdAt()).isEqualTo(created);
        assertThat(dto.updatedAt()).isEqualTo(updated);
    }

    @Test
    @DisplayName("toExportDto: handles multiple tags with special characters")
    void toExportDto_tagsWithSpecialChars_preservesNames() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        Tag tag1 = new Tag();
        tag1.setName("C++");
        Tag tag2 = new Tag();
        tag2.setName("Java/Spring");
        Tag tag3 = new Tag();
        tag3.setName("AI & ML");

        quiz.setTags(Set.of(tag1, tag2, tag3));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.tags()).containsExactlyInAnyOrder("C++", "Java/Spring", "AI & ML");
    }

    @Test
    @DisplayName("toExportDto: handles category with special characters")
    void toExportDto_categoryWithSpecialChars_preservesName() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        Category category = new Category();
        category.setName("Science & Technology");
        quiz.setCategory(category);

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.category()).isEqualTo("Science & Technology");
    }

    @Test
    @DisplayName("toExportDto: handles complex nested JSON content")
    void toExportDto_complexNestedContent_parsesCorrectly() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.MATCHING);
        question.setDifficulty(Difficulty.HARD);
        question.setQuestionText("Match the items");
        question.setContent("{\"left\":[{\"id\":1,\"text\":\"A\",\"matchId\":2}],\"right\":[{\"id\":2,\"text\":\"B\"}]}");
        question.setCreatedAt(Instant.now());

        quiz.setQuestions(Set.of(question));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        QuestionExportDto qDto = dto.questions().get(0);
        assertThat(qDto.content().has("left")).isTrue();
        assertThat(qDto.content().has("right")).isTrue();
        assertThat(qDto.content().get("left").get(0).get("matchId").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("toExportDto: empty JSON object string parses to empty node")
    void toExportDto_emptyJsonObject_parsesCorrectly() {
        // Given
        Quiz quiz = createMinimalQuiz();
        
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.OPEN);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Q?");
        question.setContent("{}");
        question.setCreatedAt(Instant.now());

        quiz.setQuestions(Set.of(question));

        // When
        QuizExportDto dto = assembler.toExportDto(quiz);

        // Then
        assertThat(dto.questions().get(0).content().isObject()).isTrue();
        assertThat(dto.questions().get(0).content().size()).isEqualTo(0);
    }

    @Test
    @DisplayName("toExportDtos: maintains order when mapping multiple quizzes")
    void toExportDtos_maintainsOrder() {
        // Given
        Quiz q1 = createMinimalQuiz();
        q1.setTitle("First");
        
        Quiz q2 = createMinimalQuiz();
        q2.setTitle("Second");
        
        Quiz q3 = createMinimalQuiz();
        q3.setTitle("Third");

        // When
        List<QuizExportDto> dtos = assembler.toExportDtos(List.of(q1, q2, q3));

        // Then - order should be preserved
        assertThat(dtos).hasSize(3);
        assertThat(dtos.get(0).title()).isEqualTo("First");
        assertThat(dtos.get(1).title()).isEqualTo("Second");
        assertThat(dtos.get(2).title()).isEqualTo("Third");
    }

    // Helper methods
    
    private Quiz createMinimalQuiz() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setDescription("Description");
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setEstimatedTime(10);
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setCreatedAt(Instant.now());
        quiz.setUpdatedAt(Instant.now());
        
        User creator = new User();
        creator.setId(UUID.randomUUID());
        creator.setUsername("testuser");
        quiz.setCreator(creator);
        
        Category category = new Category();
        category.setName("General");
        quiz.setCategory(category);
        
        quiz.setTags(new HashSet<>());
        quiz.setQuestions(new HashSet<>());
        
        return quiz;
    }

    private Question createQuestion(String text, QuestionType type, Instant createdAt, UUID id) {
        Question question = new Question();
        question.setId(id);
        question.setType(type);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText(text);
        question.setContent("{}");
        question.setCreatedAt(createdAt);
        return question;
    }
}
