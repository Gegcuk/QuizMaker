package uk.gegc.quizmaker.features.quiz.application.command;

import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;

import java.util.UUID;

public interface QuizCommandService {
    @Transactional
    UUID createQuiz(String username, CreateQuizRequest request);
}
