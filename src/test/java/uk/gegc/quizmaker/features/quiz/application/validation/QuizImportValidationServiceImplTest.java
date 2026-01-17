package uk.gegc.quizmaker.features.quiz.application.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("QuizImportValidationService")
class QuizImportValidationServiceImplTest extends BaseUnitTest {

    @Mock
    QuestionHandlerFactory questionHandlerFactory;
    @Mock
    UserRepository userRepository;
    @Mock
    AccessPolicy accessPolicy;

    @InjectMocks
    QuizImportValidationServiceImpl service;

    @Test
    @DisplayName("validateQuiz rejects null quiz")
    void validateQuiz_nullQuiz_throwsException() {
        assertThatThrownBy(() -> service.validateQuiz(null, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Quiz payload is required");
    }

    @Test
    @DisplayName("validateQuiz rejects null options")
    void validateQuiz_nullOptions_throwsException() {
        QuizImportDto quiz = minimalQuiz();

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Import options are required");
    }

    @Test
    @DisplayName("validateQuiz accepts valid quiz")
    void validateQuiz_validQuiz_passes() {
        QuizImportDto quiz = minimalQuiz();

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz rejects title that is too short")
    void validateQuiz_titleTooShort_throwsException() {
        QuizImportDto quiz = quizWithTitle("ab");

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Quiz title must be between");
    }

    @Test
    @DisplayName("validateQuiz rejects title that is too long")
    void validateQuiz_titleTooLong_throwsException() {
        QuizImportDto quiz = quizWithTitle("a".repeat(101));

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Quiz title must be between");
    }

    @Test
    @DisplayName("validateQuiz rejects null title")
    void validateQuiz_titleNull_throwsException() {
        QuizImportDto quiz = quizWithTitle(null);

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Quiz title is required");
    }

    @Test
    @DisplayName("validateQuiz accepts valid title length")
    void validateQuiz_titleValid_passes() {
        QuizImportDto quiz = quizWithTitle("abc");

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz rejects description that is too long")
    void validateQuiz_descriptionTooLong_throwsException() {
        String longDescription = "a".repeat(1001);
        QuizImportDto quiz = quizWithDescription(longDescription);

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Quiz description must be at most");
    }

    @Test
    @DisplayName("validateQuiz allows null description")
    void validateQuiz_descriptionNull_passes() {
        QuizImportDto quiz = quizWithDescription(null);

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz accepts valid description")
    void validateQuiz_descriptionValid_passes() {
        QuizImportDto quiz = quizWithDescription("Short description");

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz rejects estimated time that is too small")
    void validateQuiz_estimatedTimeTooSmall_throwsException() {
        QuizImportDto quiz = quizWithEstimatedTime(0);

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Estimated time must be between");
    }

    @Test
    @DisplayName("validateQuiz rejects estimated time that is too large")
    void validateQuiz_estimatedTimeTooLarge_throwsException() {
        QuizImportDto quiz = quizWithEstimatedTime(181);

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Estimated time must be between");
    }

    @Test
    @DisplayName("validateQuiz allows null estimated time")
    void validateQuiz_estimatedTimeNull_passes() {
        QuizImportDto quiz = quizWithEstimatedTime(null);

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz accepts valid estimated time")
    void validateQuiz_estimatedTimeValid_passes() {
        QuizImportDto quiz = quizWithEstimatedTime(1);

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz rejects PUBLIC visibility without permission")
    void validateQuiz_publicVisibilityWithoutPermission_throwsException() {
        User user = userWithUsername("user");
        when(userRepository.findByUsernameWithRolesAndPermissions("user")).thenReturn(Optional.of(user));
        when(accessPolicy.hasAny(eq(user), any(), any())).thenReturn(false);

        QuizImportDto quiz = quizWithVisibility(Visibility.PUBLIC);

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only moderators can import PUBLIC quizzes");
    }

    @Test
    @DisplayName("validateQuiz accepts PUBLIC visibility with permission")
    void validateQuiz_publicVisibilityWithPermission_passes() {
        User user = userWithUsername("user");
        when(userRepository.findByUsernameWithRolesAndPermissions("user")).thenReturn(Optional.of(user));
        when(accessPolicy.hasAny(eq(user), any(), any())).thenReturn(true);

        QuizImportDto quiz = quizWithVisibility(Visibility.PUBLIC);

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz accepts PRIVATE visibility")
    void validateQuiz_privateVisibility_passes() {
        QuizImportDto quiz = quizWithVisibility(Visibility.PRIVATE);

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz rejects too many questions")
    void validateQuiz_tooManyQuestions_throwsException() {
        QuizImportDto quiz = quizWithQuestions(Collections.nCopies(51, null));

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Quiz has too many questions");
    }

    @Test
    @DisplayName("validateQuiz accepts valid question count")
    void validateQuiz_validQuestionCount_passes() {
        QuizImportDto quiz = quizWithQuestions(List.of());

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz rejects UPSERT_BY_ID without questions")
    void validateQuiz_upsertByIdWithoutQuestions_throwsException() {
        QuizImportDto quiz = quizWithQuestions(null);
        QuizImportOptions options = optionsWithStrategy(UpsertStrategy.UPSERT_BY_ID);

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", options))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("UPSERT_BY_ID requires full questions list");
    }

    @Test
    @DisplayName("validateQuiz accepts UPSERT_BY_ID with questions")
    void validateQuiz_upsertByIdWithQuestions_passes() {
        QuizImportDto quiz = quizWithQuestions(List.of());
        QuizImportOptions options = optionsWithStrategy(UpsertStrategy.UPSERT_BY_ID);

        assertThatCode(() -> service.validateQuiz(quiz, "user", options))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz rejects blank tag name")
    void validateQuiz_blankTagName_throwsException() {
        QuizImportDto quiz = quizWithTags(List.of("  "));

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Tag names must not be blank");
    }

    @Test
    @DisplayName("validateQuiz rejects null tag name in list")
    void validateQuiz_nullTagName_throwsException() {
        QuizImportDto quiz = quizWithTags(Arrays.asList("science", null));

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Tag names must not be blank");
    }

    @Test
    @DisplayName("validateQuiz accepts valid tag names")
    void validateQuiz_validTags_passes() {
        QuizImportDto quiz = quizWithTags(List.of("science", "math"));

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz allows null tags")
    void validateQuiz_nullTags_passes() {
        QuizImportDto quiz = quizWithTags(null);

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz rejects blank category name")
    void validateQuiz_blankCategoryName_throwsException() {
        QuizImportDto quiz = quizWithCategory("   ");

        assertThatThrownBy(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Category name must not be blank");
    }

    @Test
    @DisplayName("validateQuiz accepts valid category name")
    void validateQuiz_validCategory_passes() {
        QuizImportDto quiz = quizWithCategory("History");

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuiz allows null category")
    void validateQuiz_nullCategory_passes() {
        QuizImportDto quiz = quizWithCategory(null);

        assertThatCode(() -> service.validateQuiz(quiz, "user", QuizImportOptions.defaults(10)))
                .doesNotThrowAnyException();
    }

    private QuizImportDto minimalQuiz() {
        return new QuizImportDto(
                null,
                null,
                "Quiz 1",
                null,
                null,
                null,
                10,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private QuizImportDto quizWithTitle(String title) {
        QuizImportDto base = minimalQuiz();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                title,
                base.description(),
                base.visibility(),
                base.difficulty(),
                base.estimatedTime(),
                base.tags(),
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto quizWithDescription(String description) {
        QuizImportDto base = minimalQuiz();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                description,
                base.visibility(),
                base.difficulty(),
                base.estimatedTime(),
                base.tags(),
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto quizWithEstimatedTime(Integer estimatedTime) {
        QuizImportDto base = minimalQuiz();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                base.description(),
                base.visibility(),
                base.difficulty(),
                estimatedTime,
                base.tags(),
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto quizWithVisibility(Visibility visibility) {
        QuizImportDto base = minimalQuiz();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                base.description(),
                visibility,
                base.difficulty(),
                base.estimatedTime(),
                base.tags(),
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto quizWithTags(List<String> tags) {
        QuizImportDto base = minimalQuiz();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                base.description(),
                base.visibility(),
                base.difficulty(),
                base.estimatedTime(),
                tags,
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto quizWithCategory(String category) {
        QuizImportDto base = minimalQuiz();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                base.description(),
                base.visibility(),
                base.difficulty(),
                base.estimatedTime(),
                base.tags(),
                category,
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto quizWithQuestions(List<QuestionImportDto> questions) {
        QuizImportDto base = minimalQuiz();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                base.description(),
                base.visibility(),
                base.difficulty(),
                base.estimatedTime(),
                base.tags(),
                base.category(),
                base.creatorId(),
                questions,
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportOptions optionsWithStrategy(UpsertStrategy strategy) {
        return new QuizImportOptions(strategy, false, false, false, 10);
    }

    private User userWithUsername(String username) {
        User user = new User();
        user.setUsername(username);
        return user;
    }
}
