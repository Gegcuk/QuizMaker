package uk.gegc.quizmaker.features.quiz.application.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.api.dto.EntityQuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates validation logic required before publishing a quiz.
 * Centralizing these rules keeps {@code QuizServiceImpl} lighter and
 * makes the rules reusable across command services.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuizPublishValidator {

    public static final int MINIMUM_ESTIMATED_TIME_MINUTES = 1;

    private final QuestionHandlerFactory questionHandlerFactory;
    private final ObjectMapper objectMapper;

    /**
     * Ensures the quiz satisfies all publishing requirements. Aggregates
     * violations into a single error message matching existing behaviour.
     */
    public void ensurePublishable(Quiz quiz) {
        List<String> errors = new ArrayList<>();
        validateHasQuestions(quiz, errors);
        validateEstimatedTime(quiz, errors);
        validateAllQuestionsHaveValidAnswers(quiz, errors);

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Cannot publish quiz: " + String.join("; ", errors));
        }
    }

    private void validateHasQuestions(Quiz quiz, List<String> errors) {
        if (quiz.getQuestions() == null || quiz.getQuestions().isEmpty()) {
            errors.add("Cannot publish quiz without questions");
        }
    }

    private void validateEstimatedTime(Quiz quiz, List<String> errors) {
        Integer estimatedTime = quiz.getEstimatedTime();
        if (estimatedTime == null || estimatedTime < MINIMUM_ESTIMATED_TIME_MINUTES) {
            errors.add("Quiz must have a minimum estimated time of " + MINIMUM_ESTIMATED_TIME_MINUTES + " minute(s)");
        }
    }

    private void validateAllQuestionsHaveValidAnswers(Quiz quiz, List<String> errors) {
        if (quiz.getQuestions() == null || quiz.getQuestions().isEmpty()) {
            return;
        }

        for (Question question : quiz.getQuestions()) {
            validateQuestion(question, errors);
        }
    }

    private void validateQuestion(Question question, List<String> errors) {
        try {
            var handler = questionHandlerFactory.getHandler(question.getType());
            var content = objectMapper.readTree(question.getContent());
            handler.validateContent(new EntityQuestionContentRequest(question.getType(), content));
        } catch (ValidationException e) {
            errors.add("Question '" + question.getQuestionText() + "' is invalid: " + e.getMessage());
        } catch (Exception e) {
            log.debug("Failed to validate question {} for publishing", question.getId(), e);
            errors.add("Question '" + question.getQuestionText() + "' failed validation: " + e.getMessage());
        }
    }
}
