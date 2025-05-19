package uk.gegc.quizmaker.service.quiz;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.dto.quiz.QuizSearchCriteria;
import uk.gegc.quizmaker.dto.quiz.UpdateQuizRequest;

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
}
