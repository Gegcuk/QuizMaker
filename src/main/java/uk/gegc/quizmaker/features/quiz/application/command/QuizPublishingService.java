package uk.gegc.quizmaker.features.quiz.application.command;

import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;

import java.util.UUID;

public interface QuizPublishingService {

    QuizDto setStatus(String username, UUID quizId, QuizStatus status);
}
