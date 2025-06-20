package uk.gegc.quizmaker.service.quiz;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.quiz.*;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.quiz.Visibility;

import java.util.List;
import java.util.UUID;

public interface QuizService {

    UUID createQuiz(String username, CreateQuizRequest request);

    Page<QuizDto> getQuizzes(Pageable pageable, QuizSearchCriteria quizSearchCriteria);

    QuizDto getQuizById(UUID id);

    QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest updateQuizRequest);

    void deleteQuizById(String username, UUID quizId);

    void addQuestionToQuiz(String username, UUID quizId, UUID questionId);

    void removeQuestionFromQuiz(String username, UUID quizId, UUID questionId);

    void addTagToQuiz(String username, UUID quizId, UUID tagId);

    void removeTagFromQuiz(String username, UUID quizId, UUID tagId);

    void changeCategory(String username, UUID quizId, UUID categoryId);

    QuizDto setVisibility(String name, UUID quizId, Visibility visibility);

    QuizDto setStatus(String name, UUID quizId, QuizStatus status);

    Page<QuizDto> getPublicQuizzes(Pageable pageable);

    void deleteQuizzesByIds(String name, List<UUID> quizIds);

    BulkQuizUpdateOperationResultDto bulkUpdateQuiz(String name, BulkQuizUpdateRequest request);
}
