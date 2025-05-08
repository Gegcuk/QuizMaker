package uk.gegc.quizmaker.dto.quiz;

import uk.gegc.quizmaker.model.question.Difficulty;

import java.util.List;

public record QuizSearchCriteria(
        List<String> category,
        List <String> tag,
        String authorName,
        String search,
        Difficulty difficulty
) {}
