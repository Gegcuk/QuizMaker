package uk.gegc.quizmaker.features.quiz.application.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Component
public class QuizGenerationRequestCanonicalizer {

    static final String CANONICALIZATION_VERSION = "v2-source-digest";

    private final ObjectMapper objectMapper;

    public QuizGenerationRequestCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GenerationRequestFingerprint forDocument(GenerateQuizFromDocumentRequest request) {
        Map<String, Object> command = baseCommand(request);
        command.put("source", Map.of("type", "document", "documentId", request.documentId().toString()));
        return fingerprint(command);
    }

    public GenerationRequestFingerprint forUpload(GenerateQuizFromUploadRequest request, MultipartFile file) {
        Map<String, Object> command = baseCommand(request.toGenerateQuizFromDocumentRequest(UUID_PLACEHOLDER));
        command.put("documentProcessing", processingSettings(request.chunkingStrategy().name(), request.maxChunkSize()));
        command.put("source", sourceMetadata(
                "upload",
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                uploadDigest(file)
        ));
        return fingerprint(command);
    }

    public GenerationRequestFingerprint forText(GenerateQuizFromTextRequest request) {
        Map<String, Object> command = baseCommand(request.toGenerateQuizFromDocumentRequest(UUID_PLACEHOLDER));
        command.put("documentProcessing", processingSettings(request.chunkingStrategy().name(), request.maxChunkSize()));
        command.put("source", Map.of(
                "type", "text",
                "characterCount", request.text().length(),
                "contentSha256", sha256(request.text().getBytes(StandardCharsets.UTF_8))
        ));
        return fingerprint(command);
    }

    private static final UUID UUID_PLACEHOLDER = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private Map<String, Object> baseCommand(GenerateQuizFromDocumentRequest request) {
        Map<String, Object> command = new TreeMap<>();
        command.put("canonicalizationVersion", CANONICALIZATION_VERSION);
        command.put("scope", request.quizScope().name());
        command.put("chunkIndices", sortedIntegers(request.chunkIndices()));
        command.put("chapterTitle", normalizedText(request.chapterTitle()));
        command.put("chapterNumber", request.chapterNumber());
        command.put("quizTitle", normalizedText(request.quizTitle()));
        command.put("quizDescription", normalizedText(request.quizDescription()));
        command.put("questionsPerType", sortedQuestionMatrix(request.questionsPerType()));
        command.put("difficulty", request.difficulty().name());
        command.put("estimatedTimePerQuestion", request.estimatedTimePerQuestion());
        command.put("categoryId", uuidString(request.categoryId()));
        command.put("tagIds", sortedUuids(request.tagIds()));
        command.put("language", request.language().trim().toLowerCase(java.util.Locale.ROOT));
        return command;
    }

    private Map<String, Object> sourceMetadata(
            String sourceType,
            String filename,
            String contentType,
            long size,
            String contentSha256
    ) {
        Map<String, Object> source = new TreeMap<>();
        source.put("type", sourceType);
        source.put("filename", normalizedText(filename));
        source.put("contentType", normalizedText(contentType));
        source.put("size", size);
        source.put("contentSha256", contentSha256);
        return source;
    }

    private Map<String, Object> processingSettings(String chunkingStrategy, Integer maxChunkSize) {
        Map<String, Object> settings = new TreeMap<>();
        settings.put("chunkingStrategy", chunkingStrategy);
        settings.put("maxChunkSize", maxChunkSize);
        return settings;
    }

    private GenerationRequestFingerprint fingerprint(Map<String, Object> command) {
        try {
            byte[] canonicalJson = objectMapper.writeValueAsBytes(command);
            return new GenerationRequestFingerprint(sha256(canonicalJson), CANONICALIZATION_VERSION);
        } catch (JsonProcessingException exception) {
            throw new ValidationException("Unable to canonicalize quiz-generation request");
        }
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return toHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private String uploadDigest(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8_192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return toHex(digest.digest());
        } catch (IOException exception) {
            throw new ValidationException("Unable to read upload content for idempotency validation");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private String toHex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private List<Integer> sortedIntegers(List<Integer> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().sorted().toList();
    }

    private Map<String, Integer> sortedQuestionMatrix(Map<QuestionType, Integer> questionsPerType) {
        Map<String, Integer> matrix = new TreeMap<>();
        questionsPerType.forEach((type, count) -> matrix.put(type.name(), count));
        return matrix;
    }

    private List<String> sortedUuids(List<UUID> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().map(UUID::toString).sorted().toList();
    }

    private String uuidString(UUID value) {
        return value == null ? null : value.toString();
    }

    private String normalizedText(String value) {
        return value == null ? null : value.trim();
    }
}
