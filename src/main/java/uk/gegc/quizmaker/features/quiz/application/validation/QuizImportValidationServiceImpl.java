package uk.gegc.quizmaker.features.quiz.application.validation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.question.api.dto.EntityQuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.UnsupportedQuestionTypeException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.net.URI;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QuizImportValidationServiceImpl implements QuizImportValidationService {

    private static final int MIN_TITLE_LENGTH = 3;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MIN_QUESTION_TEXT_LENGTH = 3;
    private static final int MAX_QUESTION_TEXT_LENGTH = 1000;
    private static final int MIN_ESTIMATED_TIME = 1;
    private static final int MAX_ESTIMATED_TIME = 180;
    private static final int MAX_QUESTIONS_PER_QUIZ = 50;
    private static final String CDN_HOST = "cdn.quizzence.com";

    private final QuestionHandlerFactory questionHandlerFactory;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;

    @Override
    public void validateQuiz(QuizImportDto quiz, String username, QuizImportOptions options) {
        if (quiz == null) {
            throw new ValidationException("Quiz payload is required");
        }
        if (options == null) {
            throw new ValidationException("Import options are required");
        }

        validateTitle(quiz.title());
        validateDescription(quiz.description());
        validateEstimatedTime(quiz.estimatedTime());
        validateTags(quiz.tags());
        validateCategory(quiz.category());
        validateVisibility(quiz.visibility(), username);

        if (options.strategy() == UpsertStrategy.UPSERT_BY_ID && quiz.questions() == null) {
            throw new ValidationException("UPSERT_BY_ID requires full questions list");
        }

        if (quiz.questions() != null) {
            if (quiz.questions().size() > MAX_QUESTIONS_PER_QUIZ) {
                throw new ValidationException("Quiz has too many questions (max " + MAX_QUESTIONS_PER_QUIZ + ")");
            }
            for (QuestionImportDto question : quiz.questions()) {
                validateQuestion(question, username);
            }
        }
    }

    @Override
    public void validateQuestion(QuestionImportDto question, String username) {
        if (question == null) {
            throw new ValidationException("Question payload is required");
        }
        if (question.type() == null) {
            throw new ValidationException("Question type is required");
        }
        if (question.type() == QuestionType.HOTSPOT) {
            throw new UnsupportedQuestionTypeException("HOTSPOT questions are not supported for import");
        }
        if (question.difficulty() == null) {
            throw new ValidationException("Question difficulty is required");
        }
        validateQuestionText(question.questionText());
        validateAttachmentUrl(question.attachmentUrl(), question.attachment() != null ? question.attachment().assetId() : null);

        var handler = questionHandlerFactory.getHandler(question.type());
        handler.validateContent(new EntityQuestionContentRequest(question.type(), question.content()));
    }

    private void validateTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ValidationException("Quiz title is required");
        }
        int length = title.trim().length();
        if (length < MIN_TITLE_LENGTH || length > MAX_TITLE_LENGTH) {
            throw new ValidationException("Quiz title must be between " + MIN_TITLE_LENGTH + " and " + MAX_TITLE_LENGTH + " characters");
        }
    }

    private void validateDescription(String description) {
        if (description == null) {
            return;
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ValidationException("Quiz description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
    }

    private void validateEstimatedTime(Integer estimatedTime) {
        if (estimatedTime == null) {
            return;
        }
        if (estimatedTime < MIN_ESTIMATED_TIME || estimatedTime > MAX_ESTIMATED_TIME) {
            throw new ValidationException("Estimated time must be between " + MIN_ESTIMATED_TIME + " and " + MAX_ESTIMATED_TIME + " minutes");
        }
    }

    private void validateTags(List<String> tags) {
        if (tags == null) {
            return;
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                throw new ValidationException("Tag names must not be blank");
            }
        }
    }

    private void validateCategory(String category) {
        if (category == null) {
            return;
        }
        if (category.isBlank()) {
            throw new ValidationException("Category name must not be blank");
        }
    }

    private void validateVisibility(Visibility visibility, String username) {
        if (visibility == null) {
            return;
        }
        if (visibility != Visibility.PUBLIC) {
            return;
        }
        User user = resolveUser(username);
        boolean hasModerationPermissions = accessPolicy.hasAny(user, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN);
        if (!hasModerationPermissions) {
            throw new ForbiddenException("Only moderators can import PUBLIC quizzes");
        }
    }

    private void validateQuestionText(String questionText) {
        if (questionText == null || questionText.isBlank()) {
            throw new ValidationException("Question text is required");
        }
        int length = questionText.trim().length();
        if (length < MIN_QUESTION_TEXT_LENGTH || length > MAX_QUESTION_TEXT_LENGTH) {
            throw new ValidationException("Question text must be between " + MIN_QUESTION_TEXT_LENGTH + " and " + MAX_QUESTION_TEXT_LENGTH + " characters");
        }
    }

    private void validateAttachmentUrl(String attachmentUrl, java.util.UUID attachmentAssetId) {
        if (attachmentUrl == null || attachmentUrl.isBlank()) {
            return;
        }
        if (attachmentAssetId != null) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(attachmentUrl);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("attachmentUrl must be a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ValidationException("attachmentUrl must use https");
        }
        if (!CDN_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new ValidationException("attachmentUrl must use host " + CDN_HOST);
        }
    }

    private User resolveUser(String username) {
        if (username == null || username.isBlank()) {
            throw new ValidationException("Username is required for import validation");
        }
        return userRepository.findByUsernameWithRolesAndPermissions(username)
                .or(() -> userRepository.findByEmailWithRolesAndPermissions(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));
    }
}
