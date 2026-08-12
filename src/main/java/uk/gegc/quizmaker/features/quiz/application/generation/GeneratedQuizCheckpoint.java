package uk.gegc.quizmaker.features.quiz.application.generation;

import uk.gegc.quizmaker.features.question.domain.model.Question;

import java.util.List;
import java.util.Map;

public record GeneratedQuizCheckpoint(
        int schemaVersion,
        int questionCount,
        Map<Integer, List<Question>> chunkQuestions
) {
}
