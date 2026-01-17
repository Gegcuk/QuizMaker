package uk.gegc.quizmaker.features.quiz.application.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContentHashUtil")
class ContentHashUtilTest extends BaseUnitTest {

    private static final String EMPTY_HASH = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855";
    private static final UUID QUIZ_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CREATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2024-01-02T00:00:00Z");
    private static final String DEFAULT_TITLE = "Quiz Title";
    private static final String DEFAULT_DESCRIPTION = "Quiz Description";

    private ObjectMapper objectMapper;
    private ContentHashUtil util;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        util = new ContentHashUtil(objectMapper);
    }

    @Test
    @DisplayName("calculateImportContentHash returns empty hash for null quiz")
    void calculateImportContentHash_nullQuiz_returnsEmptyHash() {
        assertThat(util.calculateImportContentHash(null)).isEqualTo(EMPTY_HASH);
    }

    @Test
    @DisplayName("calculateImportContentHash returns hash for valid quiz")
    void calculateImportContentHash_validQuiz_returnsHash() {
        String hash = util.calculateImportContentHash(baseQuiz(List.of(baseQuestion())));

        assertThat(hash).matches("[0-9A-F]{64}");
        assertThat(hash).isNotEqualTo(EMPTY_HASH);
    }

    @Test
    @DisplayName("calculateImportContentHash is deterministic")
    void calculateImportContentHash_deterministic() {
        QuizImportDto quiz = baseQuiz(List.of(baseQuestion()));

        String first = util.calculateImportContentHash(quiz);
        String second = util.calculateImportContentHash(quiz);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("calculateImportContentHash ignores question order")
    void calculateImportContentHash_orderIndependent() {
        QuestionImportDto q1 = questionWithText(UUID.fromString("00000000-0000-0000-0000-000000000010"), "Question A");
        QuestionImportDto q2 = questionWithText(UUID.fromString("00000000-0000-0000-0000-000000000011"), "Question B");

        String hashA = util.calculateImportContentHash(baseQuiz(List.of(q1, q2)));
        String hashB = util.calculateImportContentHash(baseQuiz(List.of(q2, q1)));

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash normalizes text")
    void calculateImportContentHash_normalizesText() {
        String titleA = "  CAF\u00C9  ";
        String titleB = "cafe\u0301";

        String hashA = util.calculateImportContentHash(quizWithTitle(titleA, List.of(baseQuestion())));
        String hashB = util.calculateImportContentHash(quizWithTitle(titleB, List.of(baseQuestion())));

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash normalizes whitespace")
    void calculateImportContentHash_normalizesWhitespace() {
        String descriptionA = "Hello\t   world";
        String descriptionB = "hello world";

        String hashA = util.calculateImportContentHash(quizWithDescription(descriptionA, List.of(baseQuestion())));
        String hashB = util.calculateImportContentHash(quizWithDescription(descriptionB, List.of(baseQuestion())));

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash sorts question hashes for stability")
    void calculateImportContentHash_sortsQuestions() {
        QuestionImportDto q1 = questionWithText(UUID.fromString("00000000-0000-0000-0000-000000000012"), "First question");
        QuestionImportDto q2 = questionWithText(UUID.fromString("00000000-0000-0000-0000-000000000013"), "Second question");
        QuestionImportDto q3 = questionWithText(UUID.fromString("00000000-0000-0000-0000-000000000014"), "Third question");

        String hashA = util.calculateImportContentHash(baseQuiz(List.of(q1, q2, q3)));
        String hashB = util.calculateImportContentHash(baseQuiz(List.of(q3, q1, q2)));

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash sorts object fields alphabetically")
    void calculateImportContentHash_sortsObjectFields() {
        ObjectNode contentA = objectMapper.createObjectNode();
        contentA.put("b", 2);
        contentA.put("a", 1);

        ObjectNode contentB = objectMapper.createObjectNode();
        contentB.put("a", 1);
        contentB.put("b", 2);

        QuestionImportDto q1 = questionWithContent(contentA, QuestionType.MCQ_SINGLE);
        QuestionImportDto q2 = questionWithContent(contentB, QuestionType.MCQ_SINGLE);

        String hashA = util.calculateImportContentHash(baseQuiz(List.of(q1)));
        String hashB = util.calculateImportContentHash(baseQuiz(List.of(q2)));

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash sorts arrays that should be sorted")
    void calculateImportContentHash_sortsArrays() {
        ObjectNode contentA = contentWithOptions("b", "a");
        ObjectNode contentB = contentWithOptions("a", "b");

        QuestionImportDto q1 = questionWithContent(contentA, QuestionType.MCQ_SINGLE);
        QuestionImportDto q2 = questionWithContent(contentB, QuestionType.MCQ_SINGLE);

        String hashA = util.calculateImportContentHash(baseQuiz(List.of(q1)));
        String hashB = util.calculateImportContentHash(baseQuiz(List.of(q2)));

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash handles ORDERING items when correctOrder present")
    void calculateImportContentHash_handlesOrderingItems() {
        ObjectNode contentA = orderingContent(List.of("2", "1"), List.of("1", "2"));
        ObjectNode contentB = orderingContent(List.of("1", "2"), List.of("1", "2"));

        QuestionImportDto q1 = questionWithContent(contentA, QuestionType.ORDERING);
        QuestionImportDto q2 = questionWithContent(contentB, QuestionType.ORDERING);

        String hashA = util.calculateImportContentHash(baseQuiz(List.of(q1)));
        String hashB = util.calculateImportContentHash(baseQuiz(List.of(q2)));

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash preserves items order without correctOrder")
    void calculateImportContentHash_itemsOrderPreservedWithoutCorrectOrder() {
        ObjectNode contentA = orderingContent(List.of("1", "2"), null);
        ObjectNode contentB = orderingContent(List.of("2", "1"), null);

        QuestionImportDto q1 = questionWithContent(contentA, QuestionType.ORDERING);
        QuestionImportDto q2 = questionWithContent(contentB, QuestionType.ORDERING);

        String hashA = util.calculateImportContentHash(baseQuiz(List.of(q1)));
        String hashB = util.calculateImportContentHash(baseQuiz(List.of(q2)));

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash preserves correctOrder array order")
    void calculateImportContentHash_correctOrderPreserved() {
        ObjectNode contentA = orderingContent(List.of("1", "2"), List.of("1", "2"));
        ObjectNode contentB = orderingContent(List.of("1", "2"), List.of("2", "1"));

        QuestionImportDto q1 = questionWithContent(contentA, QuestionType.ORDERING);
        QuestionImportDto q2 = questionWithContent(contentB, QuestionType.ORDERING);

        String hashA = util.calculateImportContentHash(baseQuiz(List.of(q1)));
        String hashB = util.calculateImportContentHash(baseQuiz(List.of(q2)));

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateQuestionHash includes all question fields")
    void calculateQuestionHash_includesAllFields() {
        QuestionImportDto base = baseQuestion();
        String baseHash = hashForQuestion(base);

        QuestionImportDto typeChanged = questionWithOverrides(base, QuestionType.TRUE_FALSE, base.difficulty(),
                base.questionText(), base.hint(), base.explanation(), base.content(), base.attachmentUrl(), base.attachment());
        QuestionImportDto difficultyChanged = questionWithOverrides(base, base.type(), Difficulty.HARD,
                base.questionText(), base.hint(), base.explanation(), base.content(), base.attachmentUrl(), base.attachment());
        QuestionImportDto textChanged = questionWithOverrides(base, base.type(), base.difficulty(),
                "Different text", base.hint(), base.explanation(), base.content(), base.attachmentUrl(), base.attachment());
        QuestionImportDto hintChanged = questionWithOverrides(base, base.type(), base.difficulty(),
                base.questionText(), "Different hint", base.explanation(), base.content(), base.attachmentUrl(), base.attachment());
        QuestionImportDto explanationChanged = questionWithOverrides(base, base.type(), base.difficulty(),
                base.questionText(), base.hint(), "Different explanation", base.content(), base.attachmentUrl(), base.attachment());

        assertThat(hashForQuestion(typeChanged)).isNotEqualTo(baseHash);
        assertThat(hashForQuestion(difficultyChanged)).isNotEqualTo(baseHash);
        assertThat(hashForQuestion(textChanged)).isNotEqualTo(baseHash);
        assertThat(hashForQuestion(hintChanged)).isNotEqualTo(baseHash);
        assertThat(hashForQuestion(explanationChanged)).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("calculateQuestionHash includes attachment fields")
    void calculateQuestionHash_includesAttachment() {
        QuestionImportDto base = baseQuestion();
        String baseHash = hashForQuestion(base);

        MediaRefDto attachment = new MediaRefDto(
                UUID.fromString("00000000-0000-0000-0000-000000000100"),
                null,
                "Alt text",
                "Caption text",
                null,
                null,
                null
        );
        QuestionImportDto withAttachment = questionWithOverrides(base, base.type(), base.difficulty(),
                base.questionText(), base.hint(), base.explanation(), base.content(), null, attachment);

        assertThat(hashForQuestion(withAttachment)).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("calculateQuestionHash includes content")
    void calculateQuestionHash_includesContent() {
        QuestionImportDto base = baseQuestion();
        String baseHash = hashForQuestion(base);

        ObjectNode differentContent = objectMapper.createObjectNode().put("text", "Different content");
        QuestionImportDto withDifferentContent = questionWithOverrides(base, base.type(), base.difficulty(),
                base.questionText(), base.hint(), base.explanation(), differentContent, base.attachmentUrl(), base.attachment());

        assertThat(hashForQuestion(withDifferentContent)).isNotEqualTo(baseHash);
    }

    private String hashForQuestion(QuestionImportDto question) {
        return util.calculateImportContentHash(baseQuiz(List.of(question)));
    }

    private QuizImportDto baseQuiz(List<QuestionImportDto> questions) {
        return new QuizImportDto(
                1,
                QUIZ_ID,
                DEFAULT_TITLE,
                DEFAULT_DESCRIPTION,
                Visibility.PRIVATE,
                Difficulty.EASY,
                10,
                List.of("Tag A", "Tag B"),
                "Category",
                CREATOR_ID,
                questions,
                CREATED_AT,
                UPDATED_AT
        );
    }

    private QuizImportDto quizWithTitle(String title, List<QuestionImportDto> questions) {
        return new QuizImportDto(
                1,
                QUIZ_ID,
                title,
                DEFAULT_DESCRIPTION,
                Visibility.PRIVATE,
                Difficulty.EASY,
                10,
                List.of("Tag A", "Tag B"),
                "Category",
                CREATOR_ID,
                questions,
                CREATED_AT,
                UPDATED_AT
        );
    }

    private QuizImportDto quizWithDescription(String description, List<QuestionImportDto> questions) {
        return new QuizImportDto(
                1,
                QUIZ_ID,
                DEFAULT_TITLE,
                description,
                Visibility.PRIVATE,
                Difficulty.EASY,
                10,
                List.of("Tag A", "Tag B"),
                "Category",
                CREATOR_ID,
                questions,
                CREATED_AT,
                UPDATED_AT
        );
    }

    private QuestionImportDto baseQuestion() {
        return new QuestionImportDto(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                QuestionType.MCQ_SINGLE,
                Difficulty.EASY,
                "Sample question",
                objectMapper.createObjectNode().put("text", "Content"),
                "Sample hint",
                "Sample explanation",
                null,
                null
        );
    }

    private QuestionImportDto questionWithText(UUID id, String text) {
        return new QuestionImportDto(
                id,
                QuestionType.MCQ_SINGLE,
                Difficulty.EASY,
                text,
                objectMapper.createObjectNode().put("text", text),
                "Hint",
                "Explanation",
                null,
                null
        );
    }

    private QuestionImportDto questionWithContent(JsonNode content, QuestionType type) {
        QuestionImportDto base = baseQuestion();
        return questionWithOverrides(base, type, base.difficulty(), base.questionText(), base.hint(),
                base.explanation(), content, base.attachmentUrl(), base.attachment());
    }

    private QuestionImportDto questionWithOverrides(
            QuestionImportDto base,
            QuestionType type,
            Difficulty difficulty,
            String text,
            String hint,
            String explanation,
            JsonNode content,
            String attachmentUrl,
            MediaRefDto attachment
    ) {
        return new QuestionImportDto(
                base.id(),
                type,
                difficulty,
                text,
                content,
                hint,
                explanation,
                attachmentUrl,
                attachment
        );
    }

    private ObjectNode contentWithOptions(String... ids) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode options = content.putArray("options");
        for (String id : ids) {
            options.addObject().put("id", id);
        }
        return content;
    }

    private ObjectNode orderingContent(List<String> itemIds, List<String> correctOrder) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode items = content.putArray("items");
        for (String id : itemIds) {
            items.addObject().put("id", id);
        }
        if (correctOrder != null) {
            ArrayNode order = content.putArray("correctOrder");
            correctOrder.forEach(order::add);
        }
        return content;
    }

}
