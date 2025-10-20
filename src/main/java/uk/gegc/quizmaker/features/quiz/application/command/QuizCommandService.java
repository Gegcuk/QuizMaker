package uk.gegc.quizmaker.features.quiz.application.command;

import uk.gegc.quizmaker.features.quiz.api.dto.BulkQuizUpdateOperationResultDto;
import uk.gegc.quizmaker.features.quiz.api.dto.BulkQuizUpdateRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.UpdateQuizRequest;

import java.util.List;
import java.util.UUID;

public interface QuizCommandService {

    UUID createQuiz(String username, CreateQuizRequest request);

    QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest req);

    void deleteQuizById(String username, UUID id);

    void deleteQuizzesByIds(String username, List<UUID> quizIds);

    BulkQuizUpdateOperationResultDto bulkUpdateQuiz(String username, BulkQuizUpdateRequest request);
}
