package uk.gegc.quizmaker.features.question.infra.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.Iterator;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class QuestionImportMapper {

    private final ObjectMapper objectMapper;

    public Question toEntity(QuestionImportDto dto, UpsertStrategy strategy) {
        if (dto == null) {
            throw new ValidationException("Question payload is required");
        }

        Question question = new Question();
        if (strategy == UpsertStrategy.UPSERT_BY_ID && dto.id() != null) {
            question.setId(dto.id());
        }

        question.setType(dto.type());
        question.setDifficulty(dto.difficulty());
        question.setQuestionText(dto.questionText());

        JsonNode sanitized = sanitizeContent(dto.content(), dto.type());
        question.setContent(serializeContent(sanitized));

        question.setHint(dto.hint());
        question.setExplanation(dto.explanation());

        applyAttachment(question, dto);

        return question;
    }

    private void applyAttachment(Question question, QuestionImportDto dto) {
        MediaRefDto attachment = dto.attachment();
        if (attachment != null && attachment.assetId() != null) {
            question.setAttachmentAssetId(attachment.assetId());
            question.setAttachmentUrl(null);
            return;
        }
        question.setAttachmentAssetId(null);
        question.setAttachmentUrl(dto.attachmentUrl());
    }

    private JsonNode sanitizeContent(JsonNode content, QuestionType type) {
        if (content == null) {
            return null;
        }
        JsonNode copy = content.deepCopy();
        stripMediaFields(copy);
        if (type == QuestionType.ORDERING) {
            ensureCorrectOrder(copy);
        }
        return copy;
    }

    private void stripMediaFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            JsonNode mediaNode = objectNode.get("media");
            if (mediaNode != null && mediaNode.isObject()) {
                ObjectNode mediaObject = (ObjectNode) mediaNode;
                mediaObject.remove("cdnUrl");
                mediaObject.remove("width");
                mediaObject.remove("height");
                mediaObject.remove("mimeType");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                stripMediaFields(fields.next().getValue());
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                stripMediaFields(item);
            }
        }
    }

    private void ensureCorrectOrder(JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        ObjectNode objectNode = (ObjectNode) node;
        JsonNode items = objectNode.get("items");
        if (items == null || !items.isArray()) {
            return;
        }
        JsonNode correctOrder = objectNode.get("correctOrder");
        if (correctOrder != null && correctOrder.isArray()) {
            return;
        }
        ArrayNode order = objectMapper.createArrayNode();
        for (JsonNode item : items) {
            JsonNode idNode = item.get("id");
            if (idNode != null) {
                order.add(idNode);
            }
        }
        objectNode.set("correctOrder", order);
    }

    private String serializeContent(JsonNode content) {
        if (content == null) {
            throw new ValidationException("Question content is required");
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize question content", ex);
        }
    }
}
