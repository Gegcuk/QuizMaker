package uk.gegc.quizmaker.features.quiz.application.command;

import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.util.UUID;

public interface QuizVisibilityService {
    QuizDto setVisibility(String username, UUID quizId, Visibility visibility);
}
