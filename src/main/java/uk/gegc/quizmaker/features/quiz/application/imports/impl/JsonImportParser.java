package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.application.imports.ImportParser;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;
import uk.gegc.quizmaker.shared.exception.UnsupportedQuestionTypeException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JsonImportParser implements ImportParser {

    private static final String CDN_HOST = "cdn.quizzence.com";

    private final ObjectMapper objectMapper;

    @Override
    public List<QuizImportDto> parse(InputStream input, QuizImportOptions options) {
        if (input == null) {
            throw new ValidationException("Import input stream is required");
        }
        if (options == null) {
            throw new IllegalArgumentException("QuizImportOptions is required");
        }

        try (JsonParser parser = objectMapper.getFactory().createParser(input)) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                return List.of();
            }
            return switch (token) {
                case START_ARRAY -> parseArray(parser, options, null);
                case START_OBJECT -> parseWrappedObject(parser, options);
                default -> throw new ValidationException("Import payload must be a JSON array or object wrapper");
            };
        } catch (IOException ex) {
            throw new ValidationException("Malformed JSON import payload");
        }
    }

    private List<QuizImportDto> parseWrappedObject(JsonParser parser, QuizImportOptions options) throws IOException {
        Integer schemaVersion = null;
        List<QuizImportDto> quizzes = new ArrayList<>();

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            if (fieldName == null) {
                parser.skipChildren();
                continue;
            }
            parser.nextToken();
            switch (fieldName) {
                case "schemaVersion" -> schemaVersion = readSchemaVersion(parser);
                case "quizzes" -> {
                    if (parser.currentToken() != JsonToken.START_ARRAY) {
                        throw new ValidationException("Expected 'quizzes' to be an array");
                    }
                    quizzes.addAll(parseArray(parser, options, schemaVersion));
                }
                default -> parser.skipChildren();
            }
        }
        if (schemaVersion == null) {
            return quizzes;
        }
        List<QuizImportDto> normalized = new ArrayList<>(quizzes.size());
        for (QuizImportDto quiz : quizzes) {
            normalized.add(applySchemaVersion(quiz, schemaVersion));
        }
        return normalized;
    }

    private Integer readSchemaVersion(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return parser.getIntValue();
        }
        if (token == JsonToken.VALUE_STRING) {
            String raw = parser.getValueAsString();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                throw new ValidationException("schemaVersion must be a number");
            }
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new ValidationException("schemaVersion must be a number");
    }

    private List<QuizImportDto> parseArray(JsonParser parser, QuizImportOptions options, Integer schemaVersion) throws IOException {
        List<QuizImportDto> quizzes = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                throw new ValidationException("Each quiz item must be a JSON object");
            }
            QuizImportDto quiz = objectMapper.readValue(parser, QuizImportDto.class);
            QuizImportDto sanitized = sanitizeQuiz(quiz, schemaVersion);
            quizzes.add(sanitized);
            enforceMaxItems(quizzes.size(), options.maxItems());
        }
        return quizzes;
    }

    private void enforceMaxItems(int currentCount, int maxItems) {
        if (currentCount > maxItems) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Import file exceeds max items limit of " + maxItems
            );
        }
    }

    private QuizImportDto sanitizeQuiz(QuizImportDto quiz, Integer schemaVersion) {
        List<QuestionImportDto> sanitizedQuestions = null;
        if (quiz.questions() != null) {
            sanitizedQuestions = new ArrayList<>(quiz.questions().size());
            for (QuestionImportDto question : quiz.questions()) {
                sanitizedQuestions.add(sanitizeQuestion(question));
            }
        }

        QuizImportDto sanitized = new QuizImportDto(
                quiz.schemaVersion(),
                quiz.id(),
                quiz.title(),
                quiz.description(),
                quiz.visibility(),
                quiz.difficulty(),
                quiz.estimatedTime(),
                quiz.tags(),
                quiz.category(),
                quiz.creatorId(),
                sanitizedQuestions,
                quiz.createdAt(),
                quiz.updatedAt()
        );
        return applySchemaVersion(sanitized, schemaVersion);
    }

    private QuestionImportDto sanitizeQuestion(QuestionImportDto question) {
        if (question.type() == QuestionType.HOTSPOT) {
            throw new UnsupportedQuestionTypeException("HOTSPOT questions are not supported for import");
        }

        JsonNode sanitizedContent = sanitizeContent(question.content());
        MediaRefDto sanitizedAttachment = sanitizeAttachment(question.attachment());
        validateAttachmentUrl(question.attachmentUrl(), sanitizedAttachment);

        return new QuestionImportDto(
                question.id(),
                question.type(),
                question.difficulty(),
                question.questionText(),
                sanitizedContent,
                question.hint(),
                question.explanation(),
                question.attachmentUrl(),
                sanitizedAttachment
        );
    }

    private JsonNode sanitizeContent(JsonNode content) {
        if (content == null) {
            return null;
        }
        JsonNode copy = content.deepCopy();
        stripMediaFields(copy);
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

    private MediaRefDto sanitizeAttachment(MediaRefDto attachment) {
        if (attachment == null) {
            return null;
        }
        return new MediaRefDto(
                attachment.assetId(),
                null,
                attachment.alt(),
                attachment.caption(),
                null,
                null,
                null
        );
    }

    private QuizImportDto applySchemaVersion(QuizImportDto quiz, Integer schemaVersion) {
        if (schemaVersion == null) {
            return quiz;
        }
        return new QuizImportDto(
                schemaVersion,
                quiz.id(),
                quiz.title(),
                quiz.description(),
                quiz.visibility(),
                quiz.difficulty(),
                quiz.estimatedTime(),
                quiz.tags(),
                quiz.category(),
                quiz.creatorId(),
                quiz.questions(),
                quiz.createdAt(),
                quiz.updatedAt()
        );
    }

    private void validateAttachmentUrl(String attachmentUrl, MediaRefDto attachment) {
        if (attachmentUrl == null || attachmentUrl.isBlank()) {
            return;
        }
        if (attachment != null && attachment.assetId() != null) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(attachmentUrl);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("attachmentUrl must be a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ValidationException("attachmentUrl must use https");
        }
        if (!CDN_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new ValidationException("attachmentUrl must use host " + CDN_HOST);
        }
    }
}
