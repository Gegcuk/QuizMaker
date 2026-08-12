package uk.gegc.quizmaker.features.ai.application;

import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies the approved generation quantity contract to runtime-valid questions.
 */
public final class GenerationCoveragePolicy {

    public static final int SUCCESS_THRESHOLD_PERCENT = 80;

    private GenerationCoveragePolicy() {
    }

    public static Map<QuestionType, Integer> expectedCounts(
            int selectedChunkCount,
            Map<QuestionType, Integer> requestedPerChunk
    ) {
        if (selectedChunkCount <= 0) {
            throw new IllegalArgumentException("selectedChunkCount must be positive");
        }
        if (requestedPerChunk == null || requestedPerChunk.isEmpty()) {
            throw new IllegalArgumentException("requestedPerChunk must contain at least one question type");
        }

        Map<QuestionType, Integer> expected = new EnumMap<>(QuestionType.class);
        requestedPerChunk.forEach((type, count) -> {
            if (type == null) {
                throw new IllegalArgumentException("requested question type must not be null");
            }
            if (count == null || count < 0) {
                throw new IllegalArgumentException("requested question count must not be null or negative");
            }
            if (count > 0) {
                expected.put(type, Math.multiplyExact(selectedChunkCount, count));
            }
        });

        if (expected.isEmpty()) {
            throw new IllegalArgumentException("requestedPerChunk must contain a positive question count");
        }
        return immutableEnumMap(expected);
    }

    public static Decision evaluate(
            Map<QuestionType, Integer> expectedByType,
            Difficulty requestedDifficulty,
            Map<Integer, List<Question>> generatedByChunk
    ) {
        Map<QuestionType, Integer> expected = validateExpectedCounts(expectedByType);
        Objects.requireNonNull(requestedDifficulty, "requestedDifficulty must not be null");
        Objects.requireNonNull(generatedByChunk, "generatedByChunk must not be null");

        Map<QuestionType, Integer> acceptedByType = new EnumMap<>(QuestionType.class);
        expected.keySet().forEach(type -> acceptedByType.put(type, 0));

        Map<Integer, List<Question>> acceptedByChunk = new LinkedHashMap<>();
        int generatedTotal = 0;

        List<Map.Entry<Integer, List<Question>>> chunks = generatedByChunk.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(Integer::compareTo)))
                .toList();

        for (Map.Entry<Integer, List<Question>> chunk : chunks) {
            if (chunk.getKey() == null) {
                throw new IllegalArgumentException("generated chunk index must not be null");
            }
            if (chunk.getValue() == null) {
                continue;
            }

            List<Question> acceptedQuestions = new ArrayList<>();
            for (Question question : chunk.getValue()) {
                generatedTotal = Math.incrementExact(generatedTotal);
                if (!matchesRequestedBucket(question, requestedDifficulty, expected)) {
                    continue;
                }

                QuestionType type = question.getType();
                int acceptedForType = acceptedByType.get(type);
                if (acceptedForType >= expected.get(type)) {
                    continue;
                }

                acceptedQuestions.add(question);
                acceptedByType.put(type, Math.incrementExact(acceptedForType));
            }

            if (!acceptedQuestions.isEmpty()) {
                acceptedByChunk.put(chunk.getKey(), List.copyOf(acceptedQuestions));
            }
        }

        Map<QuestionType, Integer> missingByType = new EnumMap<>(QuestionType.class);
        int requestedTotal = 0;
        int acceptedTotal = 0;
        for (Map.Entry<QuestionType, Integer> entry : expected.entrySet()) {
            int accepted = acceptedByType.get(entry.getKey());
            requestedTotal = Math.addExact(requestedTotal, entry.getValue());
            acceptedTotal = Math.addExact(acceptedTotal, accepted);
            if (accepted < entry.getValue()) {
                missingByType.put(entry.getKey(), entry.getValue() - accepted);
            }
        }

        boolean successful = (long) acceptedTotal * 100L
                > (long) requestedTotal * SUCCESS_THRESHOLD_PERCENT;

        return new Decision(
                expected,
                acceptedByType,
                missingByType,
                acceptedByChunk,
                requestedTotal,
                acceptedTotal,
                generatedTotal,
                generatedTotal - acceptedTotal,
                successful
        );
    }

    private static Map<QuestionType, Integer> validateExpectedCounts(
            Map<QuestionType, Integer> expectedByType
    ) {
        if (expectedByType == null || expectedByType.isEmpty()) {
            throw new IllegalArgumentException("expectedByType must contain at least one question type");
        }

        Map<QuestionType, Integer> expected = new EnumMap<>(QuestionType.class);
        expectedByType.forEach((type, count) -> {
            if (type == null || count == null || count <= 0) {
                throw new IllegalArgumentException("expected question types and counts must be positive");
            }
            expected.put(type, count);
        });
        return immutableEnumMap(expected);
    }

    private static boolean matchesRequestedBucket(
            Question question,
            Difficulty requestedDifficulty,
            Map<QuestionType, Integer> expected
    ) {
        return question != null
                && question.getType() != null
                && question.getDifficulty() == requestedDifficulty
                && expected.containsKey(question.getType());
    }

    private static Map<QuestionType, Integer> immutableEnumMap(Map<QuestionType, Integer> source) {
        return Collections.unmodifiableMap(new EnumMap<>(source));
    }

    public record Decision(
            Map<QuestionType, Integer> expectedByType,
            Map<QuestionType, Integer> acceptedByType,
            Map<QuestionType, Integer> missingByType,
            Map<Integer, List<Question>> acceptedByChunk,
            int requestedTotal,
            int acceptedTotal,
            int generatedTotal,
            int discardedTotal,
            boolean successful
    ) {
        public Decision {
            expectedByType = immutableEnumMap(expectedByType);
            acceptedByType = immutableEnumMap(acceptedByType);
            missingByType = immutableEnumMap(missingByType);

            Map<Integer, List<Question>> chunkCopy = new LinkedHashMap<>();
            acceptedByChunk.forEach((chunk, questions) -> chunkCopy.put(chunk, List.copyOf(questions)));
            acceptedByChunk = Collections.unmodifiableMap(chunkCopy);
        }

        public boolean partial() {
            return successful && acceptedTotal < requestedTotal;
        }

        public List<Question> acceptedQuestions() {
            return acceptedByChunk.values().stream()
                    .flatMap(List::stream)
                    .toList();
        }
    }
}
