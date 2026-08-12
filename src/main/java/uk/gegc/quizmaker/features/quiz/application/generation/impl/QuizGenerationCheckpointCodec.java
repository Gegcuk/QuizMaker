package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.application.generation.GeneratedQuizCheckpoint;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCheckpointException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class QuizGenerationCheckpointCodec {

    static final int CURRENT_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public QuizGenerationCheckpointCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    EncodedCheckpoint encode(Map<Integer, List<Question>> chunkQuestions, int maxPayloadBytes) {
        if (chunkQuestions == null || chunkQuestions.isEmpty()) {
            throw new QuizGenerationCheckpointException("Generated output must contain at least one chunk");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }

        List<ChunkSnapshot> chunks = chunkQuestions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(entry -> snapshotChunk(entry.getKey(), entry.getValue()))
                .toList();
        int questionCount = chunks.stream().mapToInt(chunk -> chunk.questions().size()).sum();
        if (questionCount <= 0) {
            throw new QuizGenerationCheckpointException("Generated output must contain at least one question");
        }

        try {
            String payload = objectMapper.writeValueAsString(new Payload(CURRENT_SCHEMA_VERSION, chunks));
            if (payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
                throw new QuizGenerationCheckpointException("Generated output exceeds the checkpoint size limit");
            }
            return new EncodedCheckpoint(CURRENT_SCHEMA_VERSION, payload, questionCount);
        } catch (JsonProcessingException exception) {
            throw new QuizGenerationCheckpointException("Generated output could not be checkpointed", exception);
        }
    }

    GeneratedQuizCheckpoint decode(
            int schemaVersion,
            String payload,
            int expectedQuestionCount,
            int maxPayloadBytes
    ) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new QuizGenerationCheckpointException(
                    "Unsupported generated-output checkpoint schema version: " + schemaVersion);
        }
        if (payload == null || payload.isBlank()) {
            throw new QuizGenerationCheckpointException("Generated-output checkpoint payload is empty");
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new QuizGenerationCheckpointException("Generated output exceeds the checkpoint size limit");
        }

        try {
            Payload decoded = objectMapper.readValue(payload, Payload.class);
            if (decoded.schemaVersion() != schemaVersion) {
                throw new QuizGenerationCheckpointException("Generated-output checkpoint schema metadata is inconsistent");
            }

            Map<Integer, List<Question>> chunks = new LinkedHashMap<>();
            Set<Integer> seenChunkIndexes = new HashSet<>();
            int actualQuestionCount = 0;
            for (ChunkSnapshot chunk : requireChunks(decoded.chunks())) {
                if (chunk.chunkIndex() < 0 || !seenChunkIndexes.add(chunk.chunkIndex())) {
                    throw new QuizGenerationCheckpointException("Generated-output checkpoint has an invalid chunk index");
                }
                List<Question> questions = requireQuestions(chunk.questions()).stream()
                        .map(this::restoreQuestion)
                        .toList();
                if (questions.isEmpty()) {
                    throw new QuizGenerationCheckpointException("Generated-output checkpoint contains an empty chunk");
                }
                actualQuestionCount += questions.size();
                chunks.put(chunk.chunkIndex(), questions);
            }
            if (actualQuestionCount <= 0 || actualQuestionCount != expectedQuestionCount) {
                throw new QuizGenerationCheckpointException("Generated-output checkpoint question count is inconsistent");
            }
            return new GeneratedQuizCheckpoint(
                    schemaVersion,
                    actualQuestionCount,
                    Collections.unmodifiableMap(new LinkedHashMap<>(chunks))
            );
        } catch (QuizGenerationCheckpointException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new QuizGenerationCheckpointException("Generated-output checkpoint is malformed", exception);
        }
    }

    private ChunkSnapshot snapshotChunk(Integer chunkIndex, List<Question> questions) {
        if (chunkIndex == null || chunkIndex < 0) {
            throw new QuizGenerationCheckpointException("Generated output has an invalid chunk index");
        }
        if (questions == null || questions.isEmpty()) {
            throw new QuizGenerationCheckpointException("Generated output contains an empty chunk");
        }
        return new ChunkSnapshot(chunkIndex, questions.stream().map(this::snapshotQuestion).toList());
    }

    private QuestionSnapshot snapshotQuestion(Question question) {
        if (question == null) {
            throw new QuizGenerationCheckpointException("Generated output contains a null question");
        }
        validateQuestion(
                question.getType(),
                question.getDifficulty(),
                question.getQuestionText(),
                question.getContent(),
                question.getHint(),
                question.getExplanation(),
                question.getAttachmentUrl()
        );
        return new QuestionSnapshot(
                question.getType(),
                question.getDifficulty(),
                question.getQuestionText(),
                question.getContent(),
                question.getHint(),
                question.getExplanation(),
                question.getAttachmentUrl(),
                question.getAttachmentAssetId()
        );
    }

    private Question restoreQuestion(QuestionSnapshot snapshot) {
        if (snapshot == null) {
            throw new QuizGenerationCheckpointException("Generated-output checkpoint contains a null question");
        }
        validateQuestion(
                snapshot.type(),
                snapshot.difficulty(),
                snapshot.questionText(),
                snapshot.content(),
                snapshot.hint(),
                snapshot.explanation(),
                snapshot.attachmentUrl()
        );
        Question question = new Question();
        question.setType(snapshot.type());
        question.setDifficulty(snapshot.difficulty());
        question.setQuestionText(snapshot.questionText());
        question.setContent(snapshot.content());
        question.setHint(snapshot.hint());
        question.setExplanation(snapshot.explanation());
        question.setAttachmentUrl(snapshot.attachmentUrl());
        question.setAttachmentAssetId(snapshot.attachmentAssetId());
        return question;
    }

    private void validateQuestion(
            QuestionType type,
            Difficulty difficulty,
            String questionText,
            String content,
            String hint,
            String explanation,
            String attachmentUrl
    ) {
        if (type == null || difficulty == null) {
            throw new QuizGenerationCheckpointException("Generated question type and difficulty are required");
        }
        requireText(questionText, 1_000, "question text");
        requireText(content, Integer.MAX_VALUE, "question content");
        requireOptionalLength(hint, 500, "question hint");
        requireOptionalLength(explanation, 2_000, "question explanation");
        requireOptionalLength(attachmentUrl, 2_048, "question attachment URL");
        try {
            objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new QuizGenerationCheckpointException("Generated question content is not valid JSON", exception);
        }
    }

    private void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new QuizGenerationCheckpointException("Generated " + field + " is required");
        }
        if (value.length() > maxLength) {
            throw new QuizGenerationCheckpointException("Generated " + field + " exceeds its storage limit");
        }
    }

    private void requireOptionalLength(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw new QuizGenerationCheckpointException("Generated " + field + " exceeds its storage limit");
        }
    }

    private List<ChunkSnapshot> requireChunks(List<ChunkSnapshot> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new QuizGenerationCheckpointException("Generated-output checkpoint contains no chunks");
        }
        return new ArrayList<>(chunks);
    }

    private List<QuestionSnapshot> requireQuestions(List<QuestionSnapshot> questions) {
        if (questions == null) {
            throw new QuizGenerationCheckpointException("Generated-output checkpoint contains no question list");
        }
        return questions;
    }

    record EncodedCheckpoint(int schemaVersion, String payload, int questionCount) {
    }

    private record Payload(int schemaVersion, List<ChunkSnapshot> chunks) {
    }

    private record ChunkSnapshot(int chunkIndex, List<QuestionSnapshot> questions) {
    }

    private record QuestionSnapshot(
            QuestionType type,
            Difficulty difficulty,
            String questionText,
            String content,
            String hint,
            String explanation,
            String attachmentUrl,
            UUID attachmentAssetId
    ) {
    }
}
