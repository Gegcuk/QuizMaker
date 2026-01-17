package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;
import uk.gegc.quizmaker.shared.exception.UnsupportedQuestionTypeException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonImportParser")
class JsonImportParserTest extends BaseUnitTest {

    private static final String TOP_MEDIA_ASSET_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
    private static final String OPTION_MEDIA_ASSET_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ITEM_MEDIA_ASSET_ID = "22222222-2222-2222-2222-222222222222";
    private static final String STATEMENT_MEDIA_ASSET_ID = "33333333-3333-3333-3333-333333333333";
    private static final String ATTACHMENT_ASSET_ID = "44444444-4444-4444-4444-444444444444";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonImportParser parser = new JsonImportParser(objectMapper);
    private final QuizImportOptions options = QuizImportOptions.defaults(10);

    @Test
    @DisplayName("parse handles JSON array payload")
    void parse_validArray_returnsListOfQuizzes() {
        String payload = """
                [
                  {"title":"Quiz 1","estimatedTime":10},
                  {"title":"Quiz 2","estimatedTime":5}
                ]
                """;

        List<QuizImportDto> result = parser.parse(input(payload), options);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).title()).isEqualTo("Quiz 1");
        assertThat(result.get(1).title()).isEqualTo("Quiz 2");
    }

    @Test
    @DisplayName("parse handles quiz with null questions array")
    void parse_quizWithNullQuestions_handlesGracefully() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions": null
                  }
                ]
                """;

        List<QuizImportDto> result = parser.parse(input(payload), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).questions()).isNull();
    }

    @Test
    @DisplayName("parse handles quiz with empty questions array")
    void parse_quizWithEmptyQuestions_handlesGracefully() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions": []
                  }
                ]
                """;

        List<QuizImportDto> result = parser.parse(input(payload), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).questions()).isEmpty();
    }

    @Test
    @DisplayName("parse handles quiz with null optional fields")
    void parse_quizWithNullFields_handlesGracefully() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "description": null,
                    "visibility": null,
                    "difficulty": null,
                    "estimatedTime":10,
                    "tags": null,
                    "category": null,
                    "creatorId": null,
                    "questions": []
                  }
                ]
                """;

        List<QuizImportDto> result = parser.parse(input(payload), options);

        QuizImportDto quiz = result.get(0);
        assertThat(quiz.description()).isNull();
        assertThat(quiz.visibility()).isNull();
        assertThat(quiz.difficulty()).isNull();
        assertThat(quiz.tags()).isNull();
        assertThat(quiz.category()).isNull();
        assertThat(quiz.creatorId()).isNull();
    }

    @Test
    @DisplayName("parse handles question with empty content JSON")
    void parse_quizWithEmptyContent_handlesGracefully() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions":[
                      {
                        "type":"MCQ_SINGLE",
                        "difficulty":"MEDIUM",
                        "questionText":"Q1",
                        "content":{}
                      }
                    ]
                  }
                ]
                """;

        QuestionImportDto question = parseSingleQuestion(payload);

        assertThat(question.content()).isNotNull();
        assertThat(question.content().isObject()).isTrue();
        assertThat(question.content().size()).isZero();
    }

    @Test
    @DisplayName("parse enforces max items limit")
    void parse_exceedsMaxItems_throwsException() {
        QuizImportOptions limited = QuizImportOptions.defaults(1);
        String payload = """
                [
                  {"title":"Quiz 1","estimatedTime":10},
                  {"title":"Quiz 2","estimatedTime":5}
                ]
                """;

        assertThatThrownBy(() -> parser.parse(input(payload), limited))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("max items limit");
    }

    @Test
    @DisplayName("parse allows exactly max items")
    void parse_exactlyMaxItems_succeeds() {
        QuizImportOptions limited = QuizImportOptions.defaults(2);
        String payload = """
                [
                  {"title":"Quiz 1","estimatedTime":10},
                  {"title":"Quiz 2","estimatedTime":5}
                ]
                """;

        List<QuizImportDto> result = parser.parse(input(payload), limited);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("parse allows below max items")
    void parse_belowMaxItems_succeeds() {
        QuizImportOptions limited = QuizImportOptions.defaults(2);
        String payload = """
                [
                  {"title":"Quiz 1","estimatedTime":10}
                ]
                """;

        List<QuizImportDto> result = parser.parse(input(payload), limited);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("parse rejects HOTSPOT questions")
    void parse_rejectsHotspotQuestions() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions":[
                      {
                        "type":"HOTSPOT",
                        "difficulty":"MEDIUM",
                        "questionText":"Q1",
                        "content":{"text":"Q1"}
                      }
                    ]
                  }
                ]
                """;

        assertThatThrownBy(() -> parser.parse(input(payload), options))
                .isInstanceOf(UnsupportedQuestionTypeException.class)
                .hasMessageContaining("HOTSPOT");
    }

    @Test
    @DisplayName("parse accepts supported question types")
    void parse_acceptsSupportedQuestionTypes() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions":[
                      {"type":"MCQ_SINGLE","difficulty":"MEDIUM","questionText":"Q1","content":{"text":"Q1"}},
                      {"type":"TRUE_FALSE","difficulty":"MEDIUM","questionText":"Q2","content":{"text":"Q2"}},
                      {"type":"OPEN","difficulty":"MEDIUM","questionText":"Q3","content":{"text":"Q3"}},
                      {"type":"FILL_GAP","difficulty":"MEDIUM","questionText":"Q4","content":{"text":"Q4"}},
                      {"type":"ORDERING","difficulty":"MEDIUM","questionText":"Q5","content":{"text":"Q5"}},
                      {"type":"COMPLIANCE","difficulty":"MEDIUM","questionText":"Q6","content":{"text":"Q6"}},
                      {"type":"MATCHING","difficulty":"MEDIUM","questionText":"Q7","content":{"text":"Q7"}}
                    ]
                  }
                ]
                """;

        List<QuestionImportDto> questions = parseQuestions(payload);

        assertThat(questions).hasSize(7);
        assertThat(questions.get(0).type()).isEqualTo(QuestionType.MCQ_SINGLE);
        assertThat(questions.get(1).type()).isEqualTo(QuestionType.TRUE_FALSE);
        assertThat(questions.get(2).type()).isEqualTo(QuestionType.OPEN);
        assertThat(questions.get(3).type()).isEqualTo(QuestionType.FILL_GAP);
        assertThat(questions.get(4).type()).isEqualTo(QuestionType.ORDERING);
        assertThat(questions.get(5).type()).isEqualTo(QuestionType.COMPLIANCE);
        assertThat(questions.get(6).type()).isEqualTo(QuestionType.MATCHING);
    }

    @Test
    @DisplayName("parse allows null question type")
    void parse_allowsNullQuestionType() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions":[
                      {
                        "difficulty":"MEDIUM",
                        "questionText":"Q1",
                        "content":{"text":"Q1"}
                      }
                    ]
                  }
                ]
                """;

        QuestionImportDto question = parseSingleQuestion(payload);

        assertThat(question.type()).isNull();
    }

    @Test
    @DisplayName("parse handles wrapped quizzes object")
    void parse_wrappedObject_returnsListOfQuizzes() {
        String payload = """
                {
                  "quizzes": [
                    {"title":"Quiz 1","estimatedTime":10},
                    {"title":"Quiz 2","estimatedTime":5}
                  ]
                }
                """;

        List<QuizImportDto> result = parser.parse(input(payload), options);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).title()).isEqualTo("Quiz 1");
        assertThat(result.get(1).title()).isEqualTo("Quiz 2");
    }

    @Test
    @DisplayName("parse prefers attachment assetId over invalid attachmentUrl")
    void parse_withAttachmentAssetId_usesAssetId() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions":[
                      {
                        "type":"MCQ_SINGLE",
                        "difficulty":"MEDIUM",
                        "questionText":"Q1",
                        "content":{"text":"Q1"},
                        "attachmentUrl":"https://example.com/bad.png",
                        "attachment":{
                          "assetId":"%s",
                          "cdnUrl":"https://cdn.quizzence.com/att.png",
                          "width":800,
                          "height":600,
                          "mimeType":"image/png"
                        }
                      }
                    ]
                  }
                ]
                """.formatted(ATTACHMENT_ASSET_ID);

        QuestionImportDto question = parseSingleQuestion(payload);

        assertThat(question.attachment().assetId()).isEqualTo(UUID.fromString(ATTACHMENT_ASSET_ID));
        assertThat(question.attachmentUrl()).isEqualTo("https://example.com/bad.png");
    }

    @Test
    @DisplayName("parse uses attachmentUrl when assetId is missing")
    void parse_withOnlyAttachmentUrl_usesUrl() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions":[
                      {
                        "type":"MCQ_SINGLE",
                        "difficulty":"MEDIUM",
                        "questionText":"Q1",
                        "content":{"text":"Q1"},
                        "attachmentUrl":"https://cdn.quizzence.com/att.png"
                      }
                    ]
                  }
                ]
                """;

        QuestionImportDto question = parseSingleQuestion(payload);

        assertThat(question.attachment()).isNull();
        assertThat(question.attachmentUrl()).isEqualTo("https://cdn.quizzence.com/att.png");
    }

    @Test
    @DisplayName("parse rejects non-cdn attachmentUrl")
    void parse_validatesAttachmentUrlHost() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions":[
                      {
                        "type":"MCQ_SINGLE",
                        "difficulty":"MEDIUM",
                        "questionText":"Q1",
                        "content":{"text":"Q1"},
                        "attachmentUrl":"https://example.com/att.png"
                      }
                    ]
                  }
                ]
                """;

        assertThatThrownBy(() -> parser.parse(input(payload), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("attachmentUrl must use host cdn.quizzence.com");
    }

    @Test
    @DisplayName("parse allows cdn.quizzence.com attachmentUrl")
    void parse_allowsCdnQuizzenceComUrls() {
        String payload = """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions":[
                      {
                        "type":"MCQ_SINGLE",
                        "difficulty":"MEDIUM",
                        "questionText":"Q1",
                        "content":{"text":"Q1"},
                        "attachmentUrl":"https://cdn.quizzence.com/att.png"
                      }
                    ]
                  }
                ]
                """;

        QuestionImportDto question = parseSingleQuestion(payload);

        assertThat(question.attachmentUrl()).isEqualTo("https://cdn.quizzence.com/att.png");
    }

    @Test
    @DisplayName("parse strips media fields from content")
    void parse_stripsMediaFieldsFromContent() {
        QuestionImportDto question = parseQuestionWithMedia();

        JsonNode media = question.content().get("media");
        assertThat(media).isNotNull();
        assertThat(media.has("cdnUrl")).isFalse();
        assertThat(media.has("width")).isFalse();
        assertThat(media.has("height")).isFalse();
        assertThat(media.has("mimeType")).isFalse();
    }

    @Test
    @DisplayName("parse strips media fields from nested objects")
    void parse_stripsMediaFieldsFromNestedObjects() {
        QuestionImportDto question = parseQuestionWithMedia();

        JsonNode content = question.content();
        JsonNode optionMedia = content.get("options").get(0).get("media");
        JsonNode itemMedia = content.get("items").get(0).get("media");
        JsonNode statementMedia = content.get("statements").get(0).get("media");

        assertThat(optionMedia.has("cdnUrl")).isFalse();
        assertThat(optionMedia.has("width")).isFalse();
        assertThat(optionMedia.has("height")).isFalse();
        assertThat(optionMedia.has("mimeType")).isFalse();

        assertThat(itemMedia.has("cdnUrl")).isFalse();
        assertThat(itemMedia.has("width")).isFalse();
        assertThat(itemMedia.has("height")).isFalse();
        assertThat(itemMedia.has("mimeType")).isFalse();

        assertThat(statementMedia.has("cdnUrl")).isFalse();
        assertThat(statementMedia.has("width")).isFalse();
        assertThat(statementMedia.has("height")).isFalse();
        assertThat(statementMedia.has("mimeType")).isFalse();
    }

    @Test
    @DisplayName("parse preserves assetId in content media")
    void parse_preservesAssetIdInMediaFields() {
        QuestionImportDto question = parseQuestionWithMedia();

        JsonNode content = question.content();
        assertThat(content.get("media").get("assetId").asText()).isEqualTo(TOP_MEDIA_ASSET_ID);
        assertThat(content.get("options").get(0).get("media").get("assetId").asText()).isEqualTo(OPTION_MEDIA_ASSET_ID);
        assertThat(content.get("items").get(0).get("media").get("assetId").asText()).isEqualTo(ITEM_MEDIA_ASSET_ID);
        assertThat(content.get("statements").get(0).get("media").get("assetId").asText()).isEqualTo(STATEMENT_MEDIA_ASSET_ID);
    }

    @Test
    @DisplayName("parse strips media fields from attachment")
    void parse_stripsMediaFieldsFromAttachment() {
        QuestionImportDto question = parseQuestionWithMedia();

        MediaRefDto attachment = question.attachment();
        assertThat(attachment).isNotNull();
        assertThat(attachment.cdnUrl()).isNull();
        assertThat(attachment.width()).isNull();
        assertThat(attachment.height()).isNull();
        assertThat(attachment.mimeType()).isNull();
    }

    @Test
    @DisplayName("parse preserves assetId in attachment")
    void parse_preservesAssetIdInAttachment() {
        QuestionImportDto question = parseQuestionWithMedia();

        assertThat(question.attachment().assetId()).isEqualTo(UUID.fromString(ATTACHMENT_ASSET_ID));
    }

    @Test
    @DisplayName("parse applies schemaVersion from wrapper")
    void parse_withSchemaVersion_appliesVersion() {
        String payload = """
                {
                  "schemaVersion": 2,
                  "quizzes": [
                    {"title":"Quiz 1","estimatedTime":10},
                    {"title":"Quiz 2","estimatedTime":5}
                  ]
                }
                """;

        List<QuizImportDto> result = parser.parse(input(payload), options);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).schemaVersion()).isEqualTo(2);
        assertThat(result.get(1).schemaVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("parse applies schemaVersion from wrapper string")
    void parse_schemaVersionInWrappedObject_appliesVersion() {
        String payload = """
                {
                  "schemaVersion": "3",
                  "quizzes": [
                    {"title":"Quiz 1","estimatedTime":10}
                  ]
                }
                """;

        List<QuizImportDto> result = parser.parse(input(payload), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).schemaVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("parse defaults schemaVersion to 1 when missing")
    void parse_withoutSchemaVersion_defaultsTo1() {
        String payload = """
                [
                  {"title":"Quiz 1","estimatedTime":10}
                ]
                """;

        List<QuizImportDto> result = parser.parse(input(payload), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).schemaVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("parse preserves schemaVersion in array items")
    void parse_schemaVersionInArrayItem_appliesVersion() {
        String payload = """
                [
                  {"schemaVersion": 4, "title":"Quiz 1","estimatedTime":10},
                  {"schemaVersion": 4, "title":"Quiz 2","estimatedTime":5}
                ]
                """;

        List<QuizImportDto> result = parser.parse(input(payload), options);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).schemaVersion()).isEqualTo(4);
        assertThat(result.get(1).schemaVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("parse handles empty array")
    void parse_emptyArray_returnsEmptyList() {
        List<QuizImportDto> result = parser.parse(input("[]"), options);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("parse rejects null input stream")
    void parse_nullInput_throwsException() {
        assertThatThrownBy(() -> parser.parse(null, options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Import input stream is required");
    }

    @Test
    @DisplayName("parse rejects malformed JSON")
    void parse_invalidJson_throwsException() {
        assertThatThrownBy(() -> parser.parse(input("{"), options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Malformed JSON import payload");
    }

    private ByteArrayInputStream input(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private QuestionImportDto parseSingleQuestion(String payload) {
        List<QuestionImportDto> questions = parseQuestions(payload);
        assertThat(questions).hasSize(1);
        return questions.get(0);
    }

    private List<QuestionImportDto> parseQuestions(String payload) {
        List<QuizImportDto> result = parser.parse(input(payload), options);
        assertThat(result).hasSize(1);
        QuizImportDto quiz = result.get(0);
        assertThat(quiz.questions()).isNotNull();
        return quiz.questions();
    }

    private QuestionImportDto parseQuestionWithMedia() {
        List<QuizImportDto> result = parser.parse(input(mediaPayload()), options);
        assertThat(result).hasSize(1);
        QuizImportDto quiz = result.get(0);
        assertThat(quiz.questions()).hasSize(1);
        return quiz.questions().get(0);
    }

    private String mediaPayload() {
        return """
                [
                  {
                    "title":"Quiz 1",
                    "estimatedTime":10,
                    "questions":[
                      {
                        "type":"MCQ_SINGLE",
                        "difficulty":"MEDIUM",
                        "questionText":"Q1",
                        "content":{
                          "text":"Q1",
                          "media":{
                            "assetId":"%s",
                            "cdnUrl":"https://cdn.quizzence.com/media.png",
                            "width":640,
                            "height":480,
                            "mimeType":"image/png",
                            "alt":"Top media"
                          },
                          "options":[
                            {
                              "id":"opt1",
                              "text":"A",
                              "media":{
                                "assetId":"%s",
                                "cdnUrl":"https://cdn.quizzence.com/opt.png",
                                "width":1,
                                "height":2,
                                "mimeType":"image/png"
                              }
                            }
                          ],
                          "items":[
                            {
                              "id":"item1",
                              "text":"I",
                              "media":{
                                "assetId":"%s",
                                "cdnUrl":"https://cdn.quizzence.com/item.png",
                                "width":3,
                                "height":4,
                                "mimeType":"image/png"
                              }
                            }
                          ],
                          "statements":[
                            {
                              "text":"S",
                              "media":{
                                "assetId":"%s",
                                "cdnUrl":"https://cdn.quizzence.com/st.png",
                                "width":5,
                                "height":6,
                                "mimeType":"image/png"
                              }
                            }
                          ]
                        },
                        "attachment":{
                          "assetId":"%s",
                          "cdnUrl":"https://cdn.quizzence.com/att.png",
                          "alt":"Attachment alt",
                          "caption":"Attachment caption",
                          "width":800,
                          "height":600,
                          "mimeType":"image/png"
                        }
                      }
                    ]
                  }
                ]
                """.formatted(
                TOP_MEDIA_ASSET_ID,
                OPTION_MEDIA_ASSET_ID,
                ITEM_MEDIA_ASSET_ID,
                STATEMENT_MEDIA_ASSET_ID,
                ATTACHMENT_ASSET_ID
        );
    }
}
