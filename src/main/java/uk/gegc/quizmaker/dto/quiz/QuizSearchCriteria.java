package uk.gegc.quizmaker.dto.quiz;

import uk.gegc.quizmaker.model.question.Difficulty;

public record QuizSearchCriteria(
        String category,
        String tag,
        String authorName,
        String search,
        Difficulty difficulty
) {}
