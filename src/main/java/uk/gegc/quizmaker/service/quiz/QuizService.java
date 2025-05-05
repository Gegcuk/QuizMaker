package uk.gegc.quizmaker.service.quiz;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.dto.quiz.QuizSearchCriteria;
import uk.gegc.quizmaker.dto.quiz.UpdateQuizRequest;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.Quiz;

import java.util.UUID;

public interface QuizService {

    UUID createQuiz(@Valid CreateQuizRequest request);
    Page<QuizDto> getQuizzes(Pageable pageable, QuizSearchCriteria quizSearchCriteria);
    QuizDto getQuizById(UUID id);
    QuizDto updateQuiz(UUID id, UpdateQuizRequest updateQuizRequest);
    void deleteQuizById(UUID quizId);
    void addQuestionToQuiz(UUID quizId, UUID questionId);
    void removeQuestionFromQuiz(UUID quizId, UUID questionId);
    void addTagToQuiz(UUID quizId, UUID tagId);
    void removeTagFromQuiz(UUID quizId, UUID tagId);
    void changeCategory(UUID quizId, UUID categoryId);
}
