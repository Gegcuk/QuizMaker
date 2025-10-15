package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.Question;

/**
 * Extracts the normalized "correct answer" structure from Question.content JSON
 * for each question type. This is used when displaying reviews of completed attempts.
 */
@Component
@RequiredArgsConstructor
public class CorrectAnswerExtractor {

    private final ObjectMapper objectMapper;

    /**
     * Extract the correct answer from a Question's content field.
     *
     * @param question the question entity containing type and content
     * @return JsonNode representing the correct answer in a normalized structure
     * @throws IllegalArgumentException if content is malformed or type is unknown
     */
    public JsonNode extractCorrectAnswer(Question question) {
        try {
            JsonNode content = objectMapper.readTree(question.getContent());
            return switch (question.getType()) {
                case MCQ_SINGLE -> extractMcqSingleCorrect(content);
                case MCQ_MULTI -> extractMcqMultiCorrect(content);
                case TRUE_FALSE -> extractTrueFalseCorrect(content);
                case OPEN -> extractOpenCorrect(content);
                case FILL_GAP -> extractFillGapCorrect(content);
                case ORDERING -> extractOrderingCorrect(content);
                case MATCHING -> extractMatchingCorrect(content);
                case HOTSPOT -> extractHotspotCorrect(content);
                case COMPLIANCE -> extractComplianceCorrect(content);
            };
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse question content for question " + question.getId(), e);
        }
    }

    /**
     * MCQ_SINGLE: { "correctOptionId": "opt_1" }
     */
    private JsonNode extractMcqSingleCorrect(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();
        if (!content.has("options")) {
            throw new IllegalArgumentException("MCQ_SINGLE content missing 'options' field");
        }

        ArrayNode options = (ArrayNode) content.get("options");
        for (JsonNode option : options) {
            if (option.has("correct") && option.get("correct").asBoolean()) {
                result.put("correctOptionId", option.get("id").asText());
                return result;
            }
        }

        throw new IllegalArgumentException("MCQ_SINGLE content has no correct option marked");
    }

    /**
     * MCQ_MULTI: { "correctOptionIds": ["opt_1", "opt_3"] }
     */
    private JsonNode extractMcqMultiCorrect(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode correctIds = objectMapper.createArrayNode();

        if (!content.has("options")) {
            throw new IllegalArgumentException("MCQ_MULTI content missing 'options' field");
        }

        ArrayNode options = (ArrayNode) content.get("options");
        for (JsonNode option : options) {
            if (option.has("correct") && option.get("correct").asBoolean()) {
                correctIds.add(option.get("id").asText());
            }
        }

        if (correctIds.isEmpty()) {
            throw new IllegalArgumentException("MCQ_MULTI content has no correct options marked");
        }

        result.set("correctOptionIds", correctIds);
        return result;
    }

    /**
     * TRUE_FALSE: { "answer": true }
     */
    private JsonNode extractTrueFalseCorrect(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();
        if (!content.has("answer")) {
            throw new IllegalArgumentException("TRUE_FALSE content missing 'answer' field");
        }
        result.put("answer", content.get("answer").asBoolean());
        return result;
    }

    /**
     * OPEN: { "answer": "expected canonical text" }
     * Note: for open questions, the correct answer might be null if manual grading is used
     */
    private JsonNode extractOpenCorrect(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();
        if (content.has("answer")) {
            result.put("answer", content.get("answer").asText());
        } else {
            // No canonical answer stored (manual grading case)
            result.putNull("answer");
        }
        return result;
    }

    /**
     * FILL_GAP: { "answers": [{"id": 1, "text": "Paris"}, {"id": 2, "text": "Tower"}] }
     */
    private JsonNode extractFillGapCorrect(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode answers = objectMapper.createArrayNode();

        if (!content.has("gaps")) {
            throw new IllegalArgumentException("FILL_GAP content missing 'gaps' field");
        }

        ArrayNode gaps = (ArrayNode) content.get("gaps");
        for (JsonNode gap : gaps) {
            if (!gap.has("id") || !gap.has("answer")) {
                throw new IllegalArgumentException("FILL_GAP gap missing 'id' or 'answer' field");
            }
            ObjectNode answerItem = objectMapper.createObjectNode();
            answerItem.put("id", gap.get("id").asInt());
            answerItem.put("text", gap.get("answer").asText());
            answers.add(answerItem);
        }

        result.set("answers", answers);
        return result;
    }

    /**
     * ORDERING: { "order": [1, 2, 3, 4] }
     */
    private JsonNode extractOrderingCorrect(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode order = objectMapper.createArrayNode();

        // If correctOrder field exists (from export shuffling), use it
        if (content.has("correctOrder")) {
            ArrayNode correctOrder = (ArrayNode) content.get("correctOrder");
            for (JsonNode id : correctOrder) {
                order.add(id.asInt());
            }
        } else {
            // Otherwise, extract from items in their current order
            if (!content.has("items")) {
                throw new IllegalArgumentException("ORDERING content missing 'items' field");
            }

            ArrayNode items = (ArrayNode) content.get("items");
            for (JsonNode item : items) {
                if (!item.has("id")) {
                    throw new IllegalArgumentException("ORDERING item missing 'id' field");
                }
                order.add(item.get("id").asInt());
            }
        }

        result.set("order", order);
        return result;
    }

    /**
     * MATCHING: { "pairs": [{"leftId": 1, "rightId": 2}, {"leftId": 2, "rightId": 1}] }
     */
    private JsonNode extractMatchingCorrect(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode pairs = objectMapper.createArrayNode();

        if (!content.has("left")) {
            throw new IllegalArgumentException("MATCHING content missing 'left' field");
        }

        ArrayNode left = (ArrayNode) content.get("left");
        for (JsonNode leftItem : left) {
            if (!leftItem.has("id") || !leftItem.has("matchId")) {
                throw new IllegalArgumentException("MATCHING left item missing 'id' or 'matchId' field");
            }
            ObjectNode pair = objectMapper.createObjectNode();
            pair.put("leftId", leftItem.get("id").asInt());
            pair.put("rightId", leftItem.get("matchId").asInt());
            pairs.add(pair);
        }

        result.set("pairs", pairs);
        return result;
    }

    /**
     * HOTSPOT: { "regionId": 2 }
     */
    private JsonNode extractHotspotCorrect(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();

        if (!content.has("regions")) {
            throw new IllegalArgumentException("HOTSPOT content missing 'regions' field");
        }

        ArrayNode regions = (ArrayNode) content.get("regions");
        for (JsonNode region : regions) {
            if (region.has("correct") && region.get("correct").asBoolean()) {
                result.put("regionId", region.get("id").asInt());
                return result;
            }
        }

        throw new IllegalArgumentException("HOTSPOT content has no correct region marked");
    }

    /**
     * COMPLIANCE: { "compliantIds": [2, 5] }
     */
    private JsonNode extractComplianceCorrect(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode compliantIds = objectMapper.createArrayNode();

        if (!content.has("statements")) {
            throw new IllegalArgumentException("COMPLIANCE content missing 'statements' field");
        }

        ArrayNode statements = (ArrayNode) content.get("statements");
        for (JsonNode statement : statements) {
            if (!statement.has("id") || !statement.has("compliant")) {
                throw new IllegalArgumentException("COMPLIANCE statement missing 'id' or 'compliant' field");
            }
            if (statement.get("compliant").asBoolean()) {
                compliantIds.add(statement.get("id").asInt());
            }
        }

        result.set("compliantIds", compliantIds);
        return result;
    }
}

