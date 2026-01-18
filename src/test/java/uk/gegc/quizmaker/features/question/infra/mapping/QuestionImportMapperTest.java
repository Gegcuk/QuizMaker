package uk.gegc.quizmaker.features.question.infra.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QuestionImportMapper Tests")
class QuestionImportMapperTest extends BaseUnitTest {

    private ObjectMapper objectMapper;
    private QuestionImportMapper mapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mapper = new QuestionImportMapper(objectMapper);
    }

    @Test
    @DisplayName("toEntity maps all DTO fields")
    void toEntity_mapsAllFields() throws Exception {
        QuestionImportDto dto = new QuestionImportDto(
                UUID.randomUUID(),
                QuestionType.MCQ_SINGLE,
                Difficulty.MEDIUM,
                "What is 2 + 2?",
                contentNode(),
                "Use basic arithmetic",
                "It is 4",
                "https://cdn.quizzence.com/attachment.png"
        );

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getType()).isEqualTo(QuestionType.MCQ_SINGLE);
        assertThat(question.getDifficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(question.getQuestionText()).isEqualTo("What is 2 + 2?");
        assertThat(question.getHint()).isEqualTo("Use basic arithmetic");
        assertThat(question.getExplanation()).isEqualTo("It is 4");
        assertThat(objectMapper.readTree(question.getContent())).isEqualTo(dto.content());
        assertThat(question.getAttachmentUrl()).isEqualTo("https://cdn.quizzence.com/attachment.png");
        assertThat(question.getAttachmentAssetId()).isNull();
    }

    @Test
    @DisplayName("toEntity maps question type")
    void toEntity_mapsType() {
        QuestionImportDto dto = dtoWithType(QuestionType.TRUE_FALSE);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getType()).isEqualTo(QuestionType.TRUE_FALSE);
    }

    @Test
    @DisplayName("toEntity maps difficulty")
    void toEntity_mapsDifficulty() {
        QuestionImportDto dto = dtoWithDifficulty(Difficulty.HARD);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getDifficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("toEntity maps question text")
    void toEntity_mapsQuestionText() {
        QuestionImportDto dto = dtoWithQuestionText("Mapped text");

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getQuestionText()).isEqualTo("Mapped text");
    }

    @Test
    @DisplayName("toEntity maps hint")
    void toEntity_mapsHint() {
        QuestionImportDto dto = dtoWithHint("Mapped hint");

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getHint()).isEqualTo("Mapped hint");
    }

    @Test
    @DisplayName("toEntity maps explanation")
    void toEntity_mapsExplanation() {
        QuestionImportDto dto = dtoWithExplanation("Mapped explanation");

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getExplanation()).isEqualTo("Mapped explanation");
    }

    @Test
    @DisplayName("toEntity prefers attachment assetId over URL")
    void toEntity_prefersAssetIdOverUrl() {
        UUID assetId = UUID.randomUUID();
        MediaRefDto attachment = new MediaRefDto(assetId, "https://cdn.quizzence.com/media.png", null, null, null, null, null);
        QuestionImportDto dto = dtoWithAttachment(attachment, "https://cdn.quizzence.com/legacy.png");

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getAttachmentAssetId()).isEqualTo(assetId);
        assertThat(question.getAttachmentUrl()).isNull();
    }

    @Test
    @DisplayName("toEntity clears attachmentUrl when assetId is provided")
    void toEntity_withAssetId_clearsUrl() {
        UUID assetId = UUID.randomUUID();
        MediaRefDto attachment = new MediaRefDto(assetId, null, null, null, null, null, null);
        QuestionImportDto dto = dtoWithAttachment(attachment, "https://cdn.quizzence.com/legacy.png");

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getAttachmentAssetId()).isEqualTo(assetId);
        assertThat(question.getAttachmentUrl()).isNull();
    }

    @Test
    @DisplayName("toEntity uses attachmentUrl when only URL is provided")
    void toEntity_withOnlyUrl_usesUrl() {
        QuestionImportDto dto = dtoWithAttachment(null, "https://cdn.quizzence.com/asset.png");

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getAttachmentAssetId()).isNull();
        assertThat(question.getAttachmentUrl()).isEqualTo("https://cdn.quizzence.com/asset.png");
    }

    @Test
    @DisplayName("toEntity prefers assetId when both attachment sources are present")
    void toEntity_withBoth_prefersAssetId() {
        UUID assetId = UUID.randomUUID();
        MediaRefDto attachment = new MediaRefDto(assetId, "https://cdn.quizzence.com/media.png", null, null, null, null, null);
        QuestionImportDto dto = dtoWithAttachment(attachment, "https://cdn.quizzence.com/legacy.png");

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getAttachmentAssetId()).isEqualTo(assetId);
        assertThat(question.getAttachmentUrl()).isNull();
    }

    @Test
    @DisplayName("toEntity strips media fields from content")
    void toEntity_stripsMediaFields() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        ObjectNode media = content.putObject("media");
        media.put("assetId", UUID.randomUUID().toString());
        media.put("cdnUrl", "https://cdn.quizzence.com/media.png");
        media.put("width", 1280);
        media.put("height", 720);
        media.put("mimeType", "image/png");
        media.put("alt", "Alt text");

        QuestionImportDto dto = dtoWithContent(content, QuestionType.MCQ_SINGLE);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());
        JsonNode sanitizedMedia = sanitized.get("media");

        assertThat(sanitizedMedia.get("assetId").asText()).isEqualTo(media.get("assetId").asText());
        assertThat(sanitizedMedia.get("alt").asText()).isEqualTo("Alt text");
        assertThat(sanitizedMedia.has("cdnUrl")).isFalse();
        assertThat(sanitizedMedia.has("width")).isFalse();
        assertThat(sanitizedMedia.has("height")).isFalse();
        assertThat(sanitizedMedia.has("mimeType")).isFalse();
    }

    @Test
    @DisplayName("toEntity preserves media assetId")
    void toEntity_preservesAssetId() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        ObjectNode media = content.putObject("media");
        media.put("assetId", UUID.randomUUID().toString());
        media.put("cdnUrl", "https://cdn.quizzence.com/media.png");

        QuestionImportDto dto = dtoWithContent(content, QuestionType.MCQ_SINGLE);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        assertThat(sanitized.get("media").get("assetId").asText()).isEqualTo(media.get("assetId").asText());
        assertThat(sanitized.get("media").has("cdnUrl")).isFalse();
    }

    @Test
    @DisplayName("toEntity strips media fields from nested content")
    void toEntity_stripsNestedMediaFields() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        ObjectNode prompt = content.putObject("prompt");
        ObjectNode promptMedia = prompt.putObject("media");
        promptMedia.put("assetId", UUID.randomUUID().toString());
        promptMedia.put("cdnUrl", "https://cdn.quizzence.com/prompt.png");
        promptMedia.put("width", 640);
        promptMedia.put("height", 480);

        ArrayNode options = content.putArray("options");
        ObjectNode option = options.addObject();
        ObjectNode optionMedia = option.putObject("media");
        optionMedia.put("assetId", UUID.randomUUID().toString());
        optionMedia.put("cdnUrl", "https://cdn.quizzence.com/option.png");
        optionMedia.put("mimeType", "image/png");

        QuestionImportDto dto = dtoWithContent(content, QuestionType.MCQ_SINGLE);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        JsonNode sanitizedPromptMedia = sanitized.get("prompt").get("media");
        assertThat(sanitizedPromptMedia.has("cdnUrl")).isFalse();
        assertThat(sanitizedPromptMedia.has("width")).isFalse();
        assertThat(sanitizedPromptMedia.has("height")).isFalse();

        JsonNode sanitizedOptionMedia = sanitized.get("options").get(0).get("media");
        assertThat(sanitizedOptionMedia.has("cdnUrl")).isFalse();
        assertThat(sanitizedOptionMedia.has("mimeType")).isFalse();
    }

    @Test
    @DisplayName("toEntity serializes content JSON to string")
    void toEntity_serializesContent() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("text", "Serialized content");
        content.put("value", 42);
        QuestionImportDto dto = dtoWithContent(content, QuestionType.MCQ_SINGLE);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getContent()).isEqualTo(objectMapper.writeValueAsString(content));
    }

    @Test
    @DisplayName("toEntity preserves ORDERING correctOrder when present")
    void toEntity_orderingWithCorrectOrder_preserves() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode items = content.putArray("items");
        items.addObject().put("id", "item-1");
        items.addObject().put("id", "item-2");
        ArrayNode correctOrder = content.putArray("correctOrder");
        correctOrder.add("item-2");
        correctOrder.add("item-1");

        QuestionImportDto dto = dtoWithContent(content, QuestionType.ORDERING);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        assertThat(sanitized.get("correctOrder")).isEqualTo(correctOrder);
    }

    @Test
    @DisplayName("toEntity derives correctOrder for ORDERING when missing")
    void toEntity_orderingWithoutCorrectOrder_derivesFromItems() throws Exception {
        ObjectNode content = orderingContent("item-1", "item-2");
        QuestionImportDto dto = dtoWithContent(content, QuestionType.ORDERING);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        assertThat(sanitized.get("correctOrder").get(0).asText()).isEqualTo("item-1");
        assertThat(sanitized.get("correctOrder").get(1).asText()).isEqualTo("item-2");
    }

    @Test
    @DisplayName("toEntity handles missing item IDs in ORDERING")
    void toEntity_orderingWithMissingItemIds_handlesGracefully() throws Exception {
        ObjectNode content = orderingContent("item-1", null, "item-3");
        QuestionImportDto dto = dtoWithContent(content, QuestionType.ORDERING);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        assertThat(sanitized.get("correctOrder").size()).isEqualTo(2);
        assertThat(sanitized.get("correctOrder").get(0).asText()).isEqualTo("item-1");
        assertThat(sanitized.get("correctOrder").get(1).asText()).isEqualTo("item-3");
    }

    @Test
    @DisplayName("toEntity CREATE_ONLY ignores incoming ID")
    void toEntity_createOnly_ignoresId() {
        QuestionImportDto dto = dtoWithId(UUID.randomUUID());

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getId()).isNull();
    }

    @Test
    @DisplayName("toEntity UPSERT_BY_ID uses incoming ID")
    void toEntity_upsertById_withId_setsId() {
        UUID id = UUID.randomUUID();
        QuestionImportDto dto = dtoWithId(id);

        Question question = mapper.toEntity(dto, UpsertStrategy.UPSERT_BY_ID);

        assertThat(question.getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("toEntity handles null hint gracefully")
    void toEntity_nullHint_handlesGracefully() {
        QuestionImportDto dto = dtoWithHint(null);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getHint()).isNull();
    }

    @Test
    @DisplayName("toEntity handles null explanation gracefully")
    void toEntity_nullExplanation_handlesGracefully() {
        QuestionImportDto dto = dtoWithExplanation(null);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getExplanation()).isNull();
    }

    @Test
    @DisplayName("toEntity handles null question text gracefully")
    void toEntity_nullQuestionText_handlesGracefully() {
        QuestionImportDto dto = dtoWithQuestionText(null);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getQuestionText()).isNull();
    }

    @Test
    @DisplayName("toEntity handles empty content object gracefully")
    void toEntity_emptyContentObject_handlesGracefully() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        QuestionImportDto dto = dtoWithContent(content, QuestionType.MCQ_SINGLE);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getContent()).isNotNull();
        JsonNode parsed = objectMapper.readTree(question.getContent());
        assertThat(parsed.size()).isZero();
    }

    @Test
    @DisplayName("toEntity strips media from all question types")
    void toEntity_stripsMediaFromAllQuestionTypes() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        ObjectNode media = content.putObject("media");
        media.put("assetId", UUID.randomUUID().toString());
        media.put("cdnUrl", "https://cdn.quizzence.com/media.png");
        media.put("width", 640);
        media.put("height", 480);

        QuestionImportDto dto = dtoWithContent(content, QuestionType.OPEN);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        assertThat(sanitized.get("media").has("cdnUrl")).isFalse();
        assertThat(sanitized.get("media").has("width")).isFalse();
        assertThat(sanitized.get("media").has("height")).isFalse();
        assertThat(sanitized.get("media").has("mimeType")).isFalse();
    }

    @Test
    @DisplayName("toEntity strips media from deeply nested structures")
    void toEntity_stripsMediaFromDeeplyNestedStructures() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        ObjectNode nested = content.putObject("level1").putObject("level2").putObject("level3");
        ObjectNode media = nested.putObject("media");
        media.put("assetId", UUID.randomUUID().toString());
        media.put("cdnUrl", "https://cdn.quizzence.com/nested.png");

        QuestionImportDto dto = dtoWithContent(content, QuestionType.MCQ_SINGLE);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        JsonNode deepMedia = sanitized.get("level1").get("level2").get("level3").get("media");
        assertThat(deepMedia.has("cdnUrl")).isFalse();
    }

    @Test
    @DisplayName("toEntity preserves media alt and caption")
    void toEntity_preservesMediaAltAndCaption() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        ObjectNode media = content.putObject("media");
        media.put("assetId", UUID.randomUUID().toString());
        media.put("alt", "Alt text");
        media.put("caption", "Caption text");
        media.put("cdnUrl", "https://cdn.quizzence.com/media.png");

        QuestionImportDto dto = dtoWithContent(content, QuestionType.OPEN);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        assertThat(sanitized.get("media").has("alt")).isTrue();
        assertThat(sanitized.get("media").has("caption")).isTrue();
    }

    @Test
    @DisplayName("toEntity handles ORDERING with empty items")
    void toEntity_orderingWithEmptyItems_handlesGracefully() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        content.putArray("items");

        QuestionImportDto dto = dtoWithContent(content, QuestionType.ORDERING);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        assertThat(sanitized.get("items").size()).isZero();
        assertThat(sanitized.get("correctOrder").size()).isZero();
    }

    @Test
    @DisplayName("toEntity handles ORDERING with items without IDs")
    void toEntity_orderingWithItemsNoIds_handlesGracefully() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode items = content.putArray("items");
        items.addObject().put("text", "Item 1");
        items.addObject().put("text", "Item 2");

        QuestionImportDto dto = dtoWithContent(content, QuestionType.ORDERING);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        assertThat(sanitized.get("correctOrder").size()).isZero();
    }

    @Test
    @DisplayName("toEntity handles ORDERING with mixed ID types correctly")
    void toEntity_orderingWithMixedIdTypes_handlesCorrectly() throws Exception {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode items = content.putArray("items");
        items.addObject().put("id", "string-id");
        items.addObject().put("id", 123);

        QuestionImportDto dto = dtoWithContent(content, QuestionType.ORDERING);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);
        JsonNode sanitized = objectMapper.readTree(question.getContent());

        assertThat(sanitized.get("correctOrder").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("toEntity handles attachment with only alt")
    void toEntity_attachmentWithOnlyAlt_handlesCorrectly() {
        MediaRefDto attachment = new MediaRefDto(UUID.randomUUID(), null, "Alt text", null, null, null, null);
        QuestionImportDto dto = dtoWithAttachment(attachment, null);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getAttachmentAssetId()).isEqualTo(attachment.assetId());
        assertThat(question.getAttachmentUrl()).isNull();
    }

    @Test
    @DisplayName("toEntity handles attachment with only caption")
    void toEntity_attachmentWithOnlyCaption_handlesCorrectly() {
        MediaRefDto attachment = new MediaRefDto(UUID.randomUUID(), null, null, "Caption text", null, null, null);
        QuestionImportDto dto = dtoWithAttachment(attachment, null);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getAttachmentAssetId()).isEqualTo(attachment.assetId());
    }

    @Test
    @DisplayName("toEntity handles null attachment URL gracefully")
    void toEntity_attachmentNullUrl_handlesGracefully() {
        QuestionImportDto dto = dtoWithAttachmentUrl(null);

        Question question = mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY);

        assertThat(question.getAttachmentUrl()).isNull();
        assertThat(question.getAttachmentAssetId()).isNull();
    }

    @Test
    @DisplayName("toEntity ignores ID for SKIP_ON_DUPLICATE strategy")
    void toEntity_skipOnDuplicate_ignoresId() {
        UUID questionId = UUID.randomUUID();
        QuestionImportDto dto = dtoWithId(questionId);

        Question question = mapper.toEntity(dto, UpsertStrategy.SKIP_ON_DUPLICATE);

        assertThat(question.getId()).isNull();
    }

    @Test
    @DisplayName("toEntity rejects null DTO")
    void toEntity_nullDto_throwsException() {
        assertThatThrownBy(() -> mapper.toEntity(null, UpsertStrategy.CREATE_ONLY))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Question payload is required");
    }

    @Test
    @DisplayName("toEntity rejects null content")
    void toEntity_nullContent_throwsException() {
        QuestionImportDto dto = dtoWithContent(null, QuestionType.MCQ_SINGLE);

        assertThatThrownBy(() -> mapper.toEntity(dto, UpsertStrategy.CREATE_ONLY))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Question content is required");
    }

    private QuestionImportDto baseDto() {
        return new QuestionImportDto(
                UUID.randomUUID(),
                QuestionType.MCQ_SINGLE,
                Difficulty.EASY,
                "Sample question",
                contentNode(),
                "Sample hint",
                "Sample explanation",
                "https://cdn.quizzence.com/attachment.png"
        );
    }

    private QuestionImportDto dtoWithType(QuestionType type) {
        QuestionImportDto base = baseDto();
        return new QuestionImportDto(
                base.id(),
                type,
                base.difficulty(),
                base.questionText(),
                base.content(),
                base.hint(),
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto dtoWithDifficulty(Difficulty difficulty) {
        QuestionImportDto base = baseDto();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                difficulty,
                base.questionText(),
                base.content(),
                base.hint(),
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto dtoWithQuestionText(String questionText) {
        QuestionImportDto base = baseDto();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                base.difficulty(),
                questionText,
                base.content(),
                base.hint(),
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto dtoWithHint(String hint) {
        QuestionImportDto base = baseDto();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                base.difficulty(),
                base.questionText(),
                base.content(),
                hint,
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto dtoWithExplanation(String explanation) {
        QuestionImportDto base = baseDto();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                base.difficulty(),
                base.questionText(),
                base.content(),
                base.hint(),
                explanation,
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto dtoWithAttachment(MediaRefDto attachment, String attachmentUrl) {
        QuestionImportDto base = baseDto();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                base.difficulty(),
                base.questionText(),
                base.content(),
                base.hint(),
                base.explanation(),
                attachmentUrl,
                attachment
        );
    }

    private QuestionImportDto dtoWithContent(JsonNode content, QuestionType type) {
        QuestionImportDto base = baseDto();
        return new QuestionImportDto(
                base.id(),
                type,
                base.difficulty(),
                base.questionText(),
                content,
                base.hint(),
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto dtoWithId(UUID id) {
        QuestionImportDto base = baseDto();
        return new QuestionImportDto(
                id,
                base.type(),
                base.difficulty(),
                base.questionText(),
                base.content(),
                base.hint(),
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto dtoWithAttachmentUrl(String attachmentUrl) {
        QuestionImportDto base = baseDto();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                base.difficulty(),
                base.questionText(),
                base.content(),
                base.hint(),
                base.explanation(),
                attachmentUrl,
                base.attachment()
        );
    }

    private ObjectNode orderingContent(String... ids) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode items = content.putArray("items");
        for (String id : ids) {
            ObjectNode item = items.addObject();
            if (id != null) {
                item.put("id", id);
            }
        }
        return content;
    }

    private JsonNode contentNode() {
        return JsonNodeFactory.instance.objectNode().put("text", "Content");
    }
}
