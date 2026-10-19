package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;


import static org.assertj.core.api.Assertions.*;

class QuestionContractsTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(QuestionType.class)
    void allFamilyGoldenExamplesMatchTheirContractsAndActualTransformations(QuestionType type) {
        for (var view : QuestionContracts.View.values()) {
            assertThatCode(() -> QuestionContracts.validate(type, view, QuestionContracts.example(type, view))).doesNotThrowAnyException();
        }
        String stored = QuestionContracts.example(type, QuestionContracts.View.AUTHOR).toString();
        var safe = new SafeQuestionContentBuilder().buildSafeContent(type, stored, true);
        assertThat(safe).isEqualTo(QuestionContracts.example(type, QuestionContracts.View.SAFE));
        Question question = new Question();
        question.setType(type); question.setContent(stored);
        assertThat(new CorrectAnswerExtractor(json).extractCorrectAnswer(question))
                .isEqualTo(QuestionContracts.example(type, QuestionContracts.View.CORRECT));
    }

    @ParameterizedTest
    @EnumSource(QuestionType.class)
    void participantDeliveryRejectsSolutionKeysEvenWhenFalseOrNull(QuestionType type) {
        for (String field : new String[]{"correct", "answer", "matchId", "compliant", "correctOrder", "explanation"}) {
            ObjectNode safe = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.SAFE);
            safe.putNull(field);
            assertThatThrownBy(() -> QuestionContracts.validate(type, QuestionContracts.View.SAFE, safe))
                    .isInstanceOf(QuestionContractException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(QuestionType.class)
    void inputExtensionsAreIgnoredButMissingAndWrongTypedResponseFieldsFail(QuestionType type) {
        ObjectNode response = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.RESPONSE);
        response.put("clientNote", "extension");
        assertThatCode(() -> QuestionContracts.validate(type, QuestionContracts.View.RESPONSE, response)).doesNotThrowAnyException();
        String required = QuestionContracts.schema(type, QuestionContracts.View.RESPONSE).path("required").get(0).asText();
        response.putObject(required);
        assertThatThrownBy(() -> QuestionContracts.validate(type, QuestionContracts.View.RESPONSE, response))
                .isInstanceOf(QuestionContractException.class);
        response.remove(required);
        assertThatThrownBy(() -> QuestionContracts.validate(type, QuestionContracts.View.RESPONSE, response))
                .isInstanceOf(QuestionContractException.class);
    }

    @Test
    void stringLimitsCountUnicodeCharactersAndArraysHaveExactBoundaries() {
        ObjectNode response = json.createObjectNode().put("answer", "x".repeat(10_000));
        QuestionContracts.validate(QuestionType.OPEN, QuestionContracts.View.RESPONSE, response);
        response.put("answer", "😀".repeat(5_000) + "x".repeat(5_000));
        QuestionContracts.validate(QuestionType.OPEN, QuestionContracts.View.RESPONSE, response);
        response.put("answer", "x".repeat(10_001));
        assertThatThrownBy(() -> QuestionContracts.validate(QuestionType.OPEN, QuestionContracts.View.RESPONSE, response))
                .isInstanceOf(QuestionContractException.class);
        ObjectNode multi = json.createObjectNode();
        var choices = multi.putArray("selectedOptionIds");
        for (int i = 0; i < 100; i++) choices.add("choice");
        QuestionContracts.validate(QuestionType.MCQ_MULTI, QuestionContracts.View.RESPONSE, multi);
        choices.add("one-too-many");
        assertThatThrownBy(() -> QuestionContracts.validate(QuestionType.MCQ_MULTI, QuestionContracts.View.RESPONSE, multi))
                .isInstanceOf(QuestionContractException.class);
    }

    @Test
    void serializedUtf8LimitCountsEscapesAndUnknownExtensions() throws Exception {
        ObjectNode value = json.createObjectNode().put("answer", "x");
        for (int i = 0; i < 6; i++) value.put("extension" + i, "x".repeat(10_000));
        value.put("padding", "");
        int padding = 65_536 - json.writeValueAsBytes(value).length;
        value.put("padding", "x".repeat(padding));
        assertThat(json.writeValueAsBytes(value)).hasSize(65_536);
        QuestionContracts.validate(QuestionType.OPEN, QuestionContracts.View.RESPONSE, value);
        value.put("padding", "x".repeat(padding + 1));
        assertThatThrownBy(() -> QuestionContracts.validate(QuestionType.OPEN, QuestionContracts.View.RESPONSE, value))
                .isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> QuestionContracts.read(QuestionType.OPEN, QuestionContracts.View.RESPONSE, value.toString()))
                .isInstanceOf(QuestionContractException.class);
    }

    @Test
    void rejectsInvalidJsonTrailingTokensUnknownFamiliesAndUnsupportedVersionsWithoutEchoingInput() {
        assertThatThrownBy(() -> QuestionContracts.read(QuestionType.TRUE_FALSE, QuestionContracts.View.AUTHOR, "{private-canary"))
                .isInstanceOf(QuestionContractException.class).hasMessageNotContaining("private-canary");
        assertThatThrownBy(() -> QuestionContracts.read(QuestionType.TRUE_FALSE, QuestionContracts.View.AUTHOR, "{\"answer\":true} {}"))
                .isInstanceOf(QuestionContractException.class);
        assertThatThrownBy(() -> QuestionContracts.validate(null, QuestionContracts.View.SAFE, json.createObjectNode()))
                .isInstanceOf(QuestionContractException.class);
        assertThatCode(() -> QuestionContracts.requireVersion(null)).doesNotThrowAnyException();
        QuestionContracts.requireVersion(json.getNodeFactory().numberNode(1));
        for (var version : new com.fasterxml.jackson.databind.JsonNode[]{json.nullNode(), json.getNodeFactory().numberNode(2), json.getNodeFactory().textNode("1")}) {
            assertThatThrownBy(() -> QuestionContracts.requireVersion(version)).isInstanceOf(QuestionContractException.class);
        }
    }

    @Test
    void preservesLegacyFillGapTypingModeAndOrderingWhileRejectingDuplicateGapAnswers() throws Exception {
        var fill = QuestionContracts.example(QuestionType.FILL_GAP, QuestionContracts.View.AUTHOR);
        assertThat(fill.has("options")).isFalse();
        QuestionContracts.validate(QuestionType.FILL_GAP, QuestionContracts.View.AUTHOR, fill);
        var response = json.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"Paris\"},{\"gapId\":1,\"answer\":\"London\"}]}");
        assertThatThrownBy(() -> QuestionContracts.validate(QuestionType.FILL_GAP, QuestionContracts.View.RESPONSE, response))
                .isInstanceOf(QuestionContractException.class);
        var ordering = (ObjectNode) QuestionContracts.example(QuestionType.ORDERING, QuestionContracts.View.AUTHOR);
        ordering.putArray("correctOrder").add(2).add(1);
        QuestionContracts.validate(QuestionType.ORDERING, QuestionContracts.View.AUTHOR, ordering);
    }

    @Test
    void existingNullableTextAndMediaAreAcceptedAndPublishedAsStandardJsonSchema() throws Exception {
        var content = json.readTree("""
                {"options":[{"id":"a","text":"Paris","media":null,"correct":true},
                {"id":"b","text":null,"media":{"assetId":"00000000-0000-0000-0000-000000000002"}}]}
                """);
        QuestionContracts.validate(QuestionType.MCQ_SINGLE, QuestionContracts.View.AUTHOR, content);
        var schema = QuestionContracts.schema(QuestionType.MCQ_SINGLE, QuestionContracts.View.AUTHOR);
        assertThat(schema.at("/properties/options/items/properties/media/type").toString()).contains("object", "null");
        assertThat(schema.toString()).doesNotContain("nullable");
        QuestionContracts.validate(QuestionType.OPEN, QuestionContracts.View.CORRECT, json.readTree("{\"answer\":null}"));
    }
}
