package uk.gegc.quizmaker.features.quiz.application.command;

import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.UpdateQuizRequest;

import java.util.UUID;

public interface QuizCommandService {

    UUID createQuiz(String username, CreateQuizRequest request);

    QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest req);
}
