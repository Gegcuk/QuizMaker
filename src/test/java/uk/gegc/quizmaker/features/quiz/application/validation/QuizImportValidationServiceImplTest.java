package uk.gegc.quizmaker.features.quiz.application.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.api.dto.EntityQuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.UnsupportedQuestionTypeException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("QuizImportValidationService")
class QuizImportValidationServiceImplTest extends BaseUnitTest {

    @Mock
    QuestionHandlerFactory questionHandlerFactory;
    @Mock
    QuestionHandler questionHandler;
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
    @DisplayName("validateQuestion rejects null question type")
    void validateQuestion_nullType_throwsException() {
        QuestionImportDto question = questionWithType(null);

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Question type is required");
    }

    @Test
    @DisplayName("validateQuestion rejects invalid question type")
    void validateQuestion_invalidType_throwsException() {
        QuestionImportDto question = questionWithType(QuestionType.MCQ_SINGLE);
        when(questionHandlerFactory.getHandler(eq(QuestionType.MCQ_SINGLE)))
                .thenThrow(new UnsupportedOperationException("No handler for type MCQ_SINGLE"));

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("No handler for type");
    }

    @Test
    @DisplayName("validateQuestion rejects HOTSPOT question type")
    void validateQuestion_hotspotType_throwsException() {
        QuestionImportDto question = questionWithType(QuestionType.HOTSPOT);

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(UnsupportedQuestionTypeException.class)
                .hasMessageContaining("HOTSPOT");
    }

    @Test
    @DisplayName("validateQuestion accepts valid question type")
    void validateQuestion_validType_passes() {
        QuestionImportDto question = questionWithType(QuestionType.MCQ_SINGLE);
        when(questionHandlerFactory.getHandler(eq(QuestionType.MCQ_SINGLE))).thenReturn(questionHandler);

        assertThatCode(() -> service.validateQuestion(question, "user"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuestion rejects question text that is too short")
    void validateQuestion_textTooShort_throwsException() {
        QuestionImportDto question = questionWithText("ab");

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Question text must be between");
    }

    @Test
    @DisplayName("validateQuestion rejects question text that is too long")
    void validateQuestion_textTooLong_throwsException() {
        QuestionImportDto question = questionWithText("a".repeat(1001));

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Question text must be between");
    }

    @Test
    @DisplayName("validateQuestion rejects null question text")
    void validateQuestion_textNull_throwsException() {
        QuestionImportDto question = questionWithText(null);

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Question text is required");
    }

    @Test
    @DisplayName("validateQuestion accepts valid question text")
    void validateQuestion_textValid_passes() {
        QuestionImportDto question = questionWithText("Valid question text");
        when(questionHandlerFactory.getHandler(eq(QuestionType.MCQ_SINGLE))).thenReturn(questionHandler);

        assertThatCode(() -> service.validateQuestion(question, "user"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuestion rejects null difficulty")
    void validateQuestion_nullDifficulty_throwsException() {
        QuestionImportDto question = questionWithDifficulty(null);

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Question difficulty is required");
    }

    @Test
    @DisplayName("validateQuestion accepts valid difficulty")
    void validateQuestion_validDifficulty_passes() {
        QuestionImportDto question = questionWithDifficulty(Difficulty.MEDIUM);
        when(questionHandlerFactory.getHandler(eq(QuestionType.MCQ_SINGLE))).thenReturn(questionHandler);

        assertThatCode(() -> service.validateQuestion(question, "user"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuestion rejects null content")
    void validateQuestion_nullContent_throwsException() {
        QuestionImportDto question = questionWithContent(null);
        when(questionHandlerFactory.getHandler(eq(QuestionType.MCQ_SINGLE))).thenReturn(questionHandler);
        doThrow(new ValidationException("Question content is required"))
                .when(questionHandler)
                .validateContent(eq(new EntityQuestionContentRequest(QuestionType.MCQ_SINGLE, null)));

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Question content is required");
    }

    @Test
    @DisplayName("validateQuestion rejects invalid content")
    void validateQuestion_invalidContent_throwsException() {
        JsonNode content = JsonNodeFactory.instance.objectNode().put("invalid", true);
        QuestionImportDto question = questionWithContent(content);
        when(questionHandlerFactory.getHandler(eq(QuestionType.MCQ_SINGLE))).thenReturn(questionHandler);
        doThrow(new ValidationException("Invalid content"))
                .when(questionHandler)
                .validateContent(eq(new EntityQuestionContentRequest(QuestionType.MCQ_SINGLE, content)));

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid content");
    }

    @Test
    @DisplayName("validateQuestion delegates content validation to handler")
    void validateQuestion_delegatesToHandler() {
        JsonNode content = JsonNodeFactory.instance.objectNode().put("key", "value");
        QuestionImportDto question = questionWithContent(content);
        when(questionHandlerFactory.getHandler(eq(QuestionType.MCQ_SINGLE))).thenReturn(questionHandler);

        service.validateQuestion(question, "user");

        verify(questionHandler).validateContent(eq(new EntityQuestionContentRequest(QuestionType.MCQ_SINGLE, content)));
    }

    @Test
    @DisplayName("validateQuestion rejects attachmentUrl with invalid host")
    void validateQuestion_invalidAttachmentUrlHost_throwsException() {
        QuestionImportDto question = questionWithAttachmentUrl("https://example.com/file.png");

        assertThatThrownBy(() -> service.validateQuestion(question, "user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("attachmentUrl must use host");
    }

    @Test
    @DisplayName("validateQuestion accepts valid attachmentUrl")
    void validateQuestion_validAttachmentUrl_passes() {
        QuestionImportDto question = questionWithAttachmentUrl("https://cdn.quizzence.com/file.png");
        when(questionHandlerFactory.getHandler(eq(QuestionType.MCQ_SINGLE))).thenReturn(questionHandler);

        assertThatCode(() -> service.validateQuestion(question, "user"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuestion allows null attachmentUrl")
    void validateQuestion_nullAttachmentUrl_passes() {
        QuestionImportDto question = questionWithAttachmentUrl(null);
        when(questionHandlerFactory.getHandler(eq(QuestionType.MCQ_SINGLE))).thenReturn(questionHandler);

        assertThatCode(() -> service.validateQuestion(question, "user"))
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

    private QuestionImportDto minimalQuestion() {
        return new QuestionImportDto(
                null,
                QuestionType.MCQ_SINGLE,
                Difficulty.EASY,
                "Valid question text",
                null,
                null,
                null,
                null
        );
    }

    private QuestionImportDto questionWithType(QuestionType type) {
        QuestionImportDto base = minimalQuestion();
        return new QuestionImportDto(
                base.id(),
                type,
                base.difficulty(),
                base.questionText(),
                base.content(),
                base.hint(),
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto questionWithText(String questionText) {
        QuestionImportDto base = minimalQuestion();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                base.difficulty(),
                questionText,
                base.content(),
                base.hint(),
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto questionWithDifficulty(Difficulty difficulty) {
        QuestionImportDto base = minimalQuestion();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                difficulty,
                base.questionText(),
                base.content(),
                base.hint(),
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto questionWithContent(JsonNode content) {
        QuestionImportDto base = minimalQuestion();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                base.difficulty(),
                base.questionText(),
                content,
                base.hint(),
                base.explanation(),
                base.attachmentUrl(),
                base.attachment()
        );
    }

    private QuestionImportDto questionWithAttachmentUrl(String attachmentUrl) {
        QuestionImportDto base = minimalQuestion();
        return new QuestionImportDto(
                base.id(),
                base.type(),
                base.difficulty(),
                base.questionText(),
                base.content(),
                base.hint(),
                base.explanation(),
                attachmentUrl,
                base.attachment()
        );
    }
}
