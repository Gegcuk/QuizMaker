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
import java.util.ArrayList;
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

    @Test
    @DisplayName("calculateImportContentHash handles null title")
    void calculateImportContentHash_nullTitle_handled() {
        QuizImportDto quiz = quizWithTitle(null, List.of(baseQuestion()));
        String hash = util.calculateImportContentHash(quiz);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles null description")
    void calculateImportContentHash_nullDescription_handled() {
        QuizImportDto quiz = quizWithDescription(null, List.of(baseQuestion()));
        String hash = util.calculateImportContentHash(quiz);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles null difficulty")
    void calculateImportContentHash_nullDifficulty_handled() {
        QuizImportDto quiz = quizWithDifficulty(null, List.of(baseQuestion()));
        String hash = util.calculateImportContentHash(quiz);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles null category")
    void calculateImportContentHash_nullCategory_handled() {
        QuizImportDto quiz = quizWithCategory(null, List.of(baseQuestion()));
        String hash = util.calculateImportContentHash(quiz);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles null tags")
    void calculateImportContentHash_nullTags_handled() {
        QuizImportDto quiz = quizWithTags(null, List.of(baseQuestion()));
        String hash = util.calculateImportContentHash(quiz);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles empty tags list")
    void calculateImportContentHash_emptyTagsList_handled() {
        QuizImportDto quiz = quizWithTags(List.of(), List.of(baseQuestion()));
        String hash = util.calculateImportContentHash(quiz);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles null estimated time")
    void calculateImportContentHash_nullEstimatedTime_handled() {
        QuizImportDto quiz = quizWithEstimatedTime(null, List.of(baseQuestion()));
        String hash = util.calculateImportContentHash(quiz);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles null questions")
    void calculateImportContentHash_nullQuestions_handled() {
        QuizImportDto quiz = baseQuiz(null);
        String hash = util.calculateImportContentHash(quiz);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles empty questions list")
    void calculateImportContentHash_emptyQuestionsList_handled() {
        QuizImportDto quiz = baseQuiz(List.of());
        String hash = util.calculateImportContentHash(quiz);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateQuestionHash handles null question text")
    void calculateQuestionHash_nullQuestionText_handled() {
        QuestionImportDto question = questionWithText(null, null);
        String hash = hashForQuestion(question);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateQuestionHash handles null question type")
    void calculateQuestionHash_nullQuestionType_handled() {
        QuestionImportDto question = questionWithType(null);
        String hash = hashForQuestion(question);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash normalizes tags case-insensitively")
    void calculateImportContentHash_tagsNormalization_caseInsensitive() {
        QuizImportDto quizA = quizWithTags(List.of("TagA", "TagB"), List.of(baseQuestion()));
        QuizImportDto quizB = quizWithTags(List.of("taga", "tagb"), List.of(baseQuestion()));

        String hashA = util.calculateImportContentHash(quizA);
        String hashB = util.calculateImportContentHash(quizB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash trims whitespace from tags")
    void calculateImportContentHash_tagsNormalization_whitespaceTrimmed() {
        QuizImportDto quizA = quizWithTags(List.of("Tag A", "Tag B"), List.of(baseQuestion()));
        QuizImportDto quizB = quizWithTags(List.of("  Tag A  ", "  Tag B  "), List.of(baseQuestion()));

        String hashA = util.calculateImportContentHash(quizA);
        String hashB = util.calculateImportContentHash(quizB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash sorts tags alphabetically")
    void calculateImportContentHash_tagsNormalization_sorted() {
        QuizImportDto quizA = quizWithTags(List.of("TagB", "TagA"), List.of(baseQuestion()));
        QuizImportDto quizB = quizWithTags(List.of("TagA", "TagB"), List.of(baseQuestion()));

        String hashA = util.calculateImportContentHash(quizA);
        String hashB = util.calculateImportContentHash(quizB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash filters null and blank tags")
    void calculateImportContentHash_tagsNormalization_filtersNullBlank() {
        List<String> tagsA = new ArrayList<>();
        tagsA.add("TagA");
        tagsA.add("TagB");
        List<String> tagsB = new ArrayList<>();
        tagsB.add("TagA");
        tagsB.add(null);
        tagsB.add("   ");
        tagsB.add("TagB");
        tagsB.add("");
        QuizImportDto quizA = quizWithTags(tagsA, List.of(baseQuestion()));
        QuizImportDto quizB = quizWithTags(tagsB, List.of(baseQuestion()));

        String hashA = util.calculateImportContentHash(quizA);
        String hashB = util.calculateImportContentHash(quizB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateQuestionHash prefers attachment assetId over URL")
    void calculateQuestionHash_attachmentAssetIdTakesPrecedence() {
        UUID assetId = UUID.randomUUID();
        MediaRefDto attachment = new MediaRefDto(assetId, null, null, null, null, null, null);
        QuestionImportDto questionA = questionWithAttachment(attachment, "https://cdn.quizzence.com/url.png");
        QuestionImportDto questionB = questionWithAttachment(attachment, "https://cdn.quizzence.com/different.png");

        String hashA = hashForQuestion(questionA);
        String hashB = hashForQuestion(questionB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateQuestionHash includes attachment URL when assetId missing")
    void calculateQuestionHash_attachmentUrlWhenAssetIdMissing() {
        QuestionImportDto questionA = questionWithAttachmentUrl("https://cdn.quizzence.com/url1.png");
        QuestionImportDto questionB = questionWithAttachmentUrl("https://cdn.quizzence.com/url2.png");

        String hashA = hashForQuestion(questionA);
        String hashB = hashForQuestion(questionB);

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateQuestionHash includes attachment alt and caption")
    void calculateQuestionHash_attachmentAltAndCaption() {
        MediaRefDto attachment1 = new MediaRefDto(UUID.randomUUID(), null, "Alt 1", "Caption 1", null, null, null);
        MediaRefDto attachment2 = new MediaRefDto(UUID.randomUUID(), null, "Alt 2", "Caption 2", null, null, null);
        QuestionImportDto questionA = questionWithAttachment(attachment1, null);
        QuestionImportDto questionB = questionWithAttachment(attachment2, null);

        String hashA = hashForQuestion(questionA);
        String hashB = hashForQuestion(questionB);

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateQuestionHash handles OPEN question content")
    void calculateQuestionHash_openQuestionContent() {
        ObjectNode content = objectMapper.createObjectNode().put("answer", "Sample answer");
        QuestionImportDto question = questionWithContent(content, QuestionType.OPEN);
        String hash = hashForQuestion(question);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateQuestionHash handles TRUE_FALSE question content")
    void calculateQuestionHash_trueFalseQuestionContent() {
        ObjectNode content = objectMapper.createObjectNode().put("answer", true);
        QuestionImportDto question = questionWithContent(content, QuestionType.TRUE_FALSE);
        String hash = hashForQuestion(question);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateQuestionHash handles FILL_GAP question content")
    void calculateQuestionHash_fillGapQuestionContent() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("text", "Fill the gap");
        ArrayNode gaps = content.putArray("gaps");
        gaps.addObject().put("id", 1).put("answer", "gap1");
        QuestionImportDto question = questionWithContent(content, QuestionType.FILL_GAP);
        String hash = hashForQuestion(question);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateQuestionHash handles COMPLIANCE question content")
    void calculateQuestionHash_complianceQuestionContent() {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode statements = content.putArray("statements");
        statements.addObject().put("text", "S1").put("compliant", true);
        QuestionImportDto question = questionWithContent(content, QuestionType.COMPLIANCE);
        String hash = hashForQuestion(question);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash sorts options array")
    void calculateImportContentHash_sortsOptionsArray() {
        ObjectNode contentA = contentWithOptions("opt2", "opt1", "opt3");
        ObjectNode contentB = contentWithOptions("opt1", "opt2", "opt3");
        QuestionImportDto questionA = questionWithContent(contentA, QuestionType.MCQ_SINGLE);
        QuestionImportDto questionB = questionWithContent(contentB, QuestionType.MCQ_SINGLE);

        String hashA = hashForQuestion(questionA);
        String hashB = hashForQuestion(questionB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash sorts statements array")
    void calculateImportContentHash_sortsStatementsArray() {
        ObjectNode contentA = objectMapper.createObjectNode();
        ArrayNode statementsA = contentA.putArray("statements");
        statementsA.addObject().put("text", "S2");
        statementsA.addObject().put("text", "S1");
        ObjectNode contentB = objectMapper.createObjectNode();
        ArrayNode statementsB = contentB.putArray("statements");
        statementsB.addObject().put("text", "S1");
        statementsB.addObject().put("text", "S2");
        QuestionImportDto questionA = questionWithContent(contentA, QuestionType.COMPLIANCE);
        QuestionImportDto questionB = questionWithContent(contentB, QuestionType.COMPLIANCE);

        String hashA = hashForQuestion(questionA);
        String hashB = hashForQuestion(questionB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash sorts gaps array")
    void calculateImportContentHash_sortsGapsArray() {
        ObjectNode contentA = objectMapper.createObjectNode();
        contentA.put("text", "Text");
        ArrayNode gapsA = contentA.putArray("gaps");
        gapsA.addObject().put("id", 2).put("answer", "gap2");
        gapsA.addObject().put("id", 1).put("answer", "gap1");
        ObjectNode contentB = objectMapper.createObjectNode();
        contentB.put("text", "Text");
        ArrayNode gapsB = contentB.putArray("gaps");
        gapsB.addObject().put("id", 1).put("answer", "gap1");
        gapsB.addObject().put("id", 2).put("answer", "gap2");
        QuestionImportDto questionA = questionWithContent(contentA, QuestionType.FILL_GAP);
        QuestionImportDto questionB = questionWithContent(contentB, QuestionType.FILL_GAP);

        String hashA = hashForQuestion(questionA);
        String hashB = hashForQuestion(questionB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateImportContentHash sorts left and right arrays in MATCHING")
    void calculateImportContentHash_sortsMatchingArrays() {
        ObjectNode contentA = objectMapper.createObjectNode();
        ArrayNode leftA = contentA.putArray("left");
        leftA.addObject().put("id", "l2");
        leftA.addObject().put("id", "l1");
        ArrayNode rightA = contentA.putArray("right");
        rightA.addObject().put("id", "r2");
        rightA.addObject().put("id", "r1");
        ObjectNode contentB = objectMapper.createObjectNode();
        ArrayNode leftB = contentB.putArray("left");
        leftB.addObject().put("id", "l1");
        leftB.addObject().put("id", "l2");
        ArrayNode rightB = contentB.putArray("right");
        rightB.addObject().put("id", "r1");
        rightB.addObject().put("id", "r2");
        QuestionImportDto questionA = questionWithContent(contentA, QuestionType.MATCHING);
        QuestionImportDto questionB = questionWithContent(contentB, QuestionType.MATCHING);

        String hashA = hashForQuestion(questionA);
        String hashB = hashForQuestion(questionB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateQuestionHash normalizes attachment URL")
    void calculateQuestionHash_normalizesAttachmentUrl() {
        QuestionImportDto questionA = questionWithAttachmentUrl("  https://cdn.quizzence.com/url.png  ");
        QuestionImportDto questionB = questionWithAttachmentUrl("https://cdn.quizzence.com/url.png");

        String hashA = hashForQuestion(questionA);
        String hashB = hashForQuestion(questionB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateQuestionHash handles null attachment URL")
    void calculateQuestionHash_nullAttachmentUrl_handled() {
        QuestionImportDto question = questionWithAttachmentUrl(null);
        String hash = hashForQuestion(question);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles deeply nested content structures")
    void calculateImportContentHash_deeplyNestedStructures() {
        ObjectNode content = objectMapper.createObjectNode();
        ObjectNode level1 = content.putObject("level1");
        ObjectNode level2 = level1.putObject("level2");
        ObjectNode level3 = level2.putObject("level3");
        level3.put("value", "deep");
        QuestionImportDto question = questionWithContent(content, QuestionType.MCQ_SINGLE);
        String hash = hashForQuestion(question);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash handles nested arrays in content")
    void calculateImportContentHash_nestedArraysInContent() {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode outer = content.putArray("outer");
        ObjectNode inner = outer.addObject();
        ArrayNode innerArray = inner.putArray("inner");
        innerArray.add("value1");
        innerArray.add("value2");
        QuestionImportDto question = questionWithContent(content, QuestionType.MCQ_SINGLE);
        String hash = hashForQuestion(question);

        assertThat(hash).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("calculateImportContentHash treats empty string and null description the same (both normalized to empty)")
    void calculateImportContentHash_emptyStringVsNullDescription_different() {
        // normalizeText converts both null and empty string to ""
        QuizImportDto quizA = quizWithDescription("", List.of(baseQuestion()));
        QuizImportDto quizB = quizWithDescription(null, List.of(baseQuestion()));

        String hashA = util.calculateImportContentHash(quizA);
        String hashB = util.calculateImportContentHash(quizB);

        // Both are normalized to empty string, so hashes are the same
        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    @DisplayName("calculateQuestionHash treats empty string and null hint the same (both normalized to empty)")
    void calculateQuestionHash_emptyStringVsNullHint_different() {
        // normalizeText converts both null and empty string to ""
        QuestionImportDto questionA = questionWithHint("");
        QuestionImportDto questionB = questionWithHint(null);

        String hashA = hashForQuestion(questionA);
        String hashB = hashForQuestion(questionB);

        // Both are normalized to empty string, so hashes are the same
        assertThat(hashA).isEqualTo(hashB);
    }

    private QuizImportDto quizWithDifficulty(Difficulty difficulty, List<QuestionImportDto> questions) {
        QuizImportDto base = baseQuiz(questions);
        return new QuizImportDto(
                base.schemaVersion(), base.id(), base.title(), base.description(),
                base.visibility(), difficulty, base.estimatedTime(),
                base.tags(), base.category(), base.creatorId(),
                base.questions(), base.createdAt(), base.updatedAt()
        );
    }

    private QuizImportDto quizWithCategory(String category, List<QuestionImportDto> questions) {
        QuizImportDto base = baseQuiz(questions);
        return new QuizImportDto(
                base.schemaVersion(), base.id(), base.title(), base.description(),
                base.visibility(), base.difficulty(), base.estimatedTime(),
                base.tags(), category, base.creatorId(),
                base.questions(), base.createdAt(), base.updatedAt()
        );
    }

    private QuizImportDto quizWithTags(List<String> tags, List<QuestionImportDto> questions) {
        QuizImportDto base = baseQuiz(questions);
        return new QuizImportDto(
                base.schemaVersion(), base.id(), base.title(), base.description(),
                base.visibility(), base.difficulty(), base.estimatedTime(),
                tags, base.category(), base.creatorId(),
                base.questions(), base.createdAt(), base.updatedAt()
        );
    }

    private QuizImportDto quizWithEstimatedTime(Integer estimatedTime, List<QuestionImportDto> questions) {
        QuizImportDto base = baseQuiz(questions);
        return new QuizImportDto(
                base.schemaVersion(), base.id(), base.title(), base.description(),
                base.visibility(), base.difficulty(), estimatedTime,
                base.tags(), base.category(), base.creatorId(),
                base.questions(), base.createdAt(), base.updatedAt()
        );
    }

    private QuestionImportDto questionWithType(QuestionType type) {
        QuestionImportDto base = baseQuestion();
        return questionWithOverrides(base, type, base.difficulty(), base.questionText(),
                base.hint(), base.explanation(), base.content(), base.attachmentUrl(), base.attachment());
    }

    private QuestionImportDto questionWithHint(String hint) {
        QuestionImportDto base = baseQuestion();
        return questionWithOverrides(base, base.type(), base.difficulty(), base.questionText(),
                hint, base.explanation(), base.content(), base.attachmentUrl(), base.attachment());
    }

    private QuestionImportDto questionWithAttachment(MediaRefDto attachment, String attachmentUrl) {
        QuestionImportDto base = baseQuestion();
        return questionWithOverrides(base, base.type(), base.difficulty(), base.questionText(),
                base.hint(), base.explanation(), base.content(), attachmentUrl, attachment);
    }

    private QuestionImportDto questionWithAttachmentUrl(String attachmentUrl) {
        QuestionImportDto base = baseQuestion();
        return questionWithOverrides(base, base.type(), base.difficulty(), base.questionText(),
                base.hint(), base.explanation(), base.content(), attachmentUrl, base.attachment());
    }

}
