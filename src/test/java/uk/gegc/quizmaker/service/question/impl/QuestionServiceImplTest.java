package uk.gegc.quizmaker.service.question.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest;
import uk.gegc.quizmaker.features.question.api.dto.QuestionDto;
import uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest;
import uk.gegc.quizmaker.features.question.application.impl.QuestionServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("QuestionServiceImpl Unit Tests")
class QuestionServiceImplTest {

    private static final String DUMMY_USER = "testUser";

    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private QuizRepository quizRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private QuestionHandlerFactory factory;
    @Mock
    private QuestionHandler handler;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;

    @InjectMocks
    private QuestionServiceImpl questionService;

    private ObjectMapper objectMapper;
    private User testUser;

    @BeforeEach
    void setUp() {
        lenient().when(factory.getHandler(any())).thenReturn(handler);
        objectMapper = new ObjectMapper();
        testUser = createTestUser();
        setupUserRepositoryMock();
        setupPermissionEvaluatorMock();
    }

    private User createTestUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(DUMMY_USER);
        user.setEmail("test@example.com");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        
        // Create a basic role with minimal permissions
        Role role = new Role();
        role.setRoleId(1L);
        role.setRoleName("ROLE_USER");
        role.setDescription("Basic user role");
        
        // Create permissions for the role
        Permission permission1 = new Permission();
        permission1.setPermissionId(1L);
        permission1.setPermissionName("QUESTION_CREATE");
        permission1.setResource("question");
        permission1.setAction("create");
        
        Permission permission2 = new Permission();
        permission2.setPermissionId(2L);
        permission2.setPermissionName("QUESTION_UPDATE");
        permission2.setResource("question");
        permission2.setAction("update");
        
        Permission permission3 = new Permission();
        permission3.setPermissionId(3L);
        permission3.setPermissionName("QUESTION_DELETE");
        permission3.setResource("question");
        permission3.setAction("delete");
        
        Set<Permission> permissions = new HashSet<>();
        permissions.add(permission1);
        permissions.add(permission2);
        permissions.add(permission3);
        
        role.setPermissions(permissions);
        
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        user.setRoles(roles);
        
        return user;
    }

    private void setupUserRepositoryMock() {
        lenient().when(userRepository.findByUsername(DUMMY_USER)).thenReturn(Optional.of(testUser));
        lenient().when(userRepository.findByEmail(DUMMY_USER)).thenReturn(Optional.empty());
    }

    private void setupPermissionEvaluatorMock() {
        // By default, user has basic permissions
        lenient().when(appPermissionEvaluator.hasPermission(eq(testUser), any(PermissionName.class)))
                .thenReturn(false);
        
        // Allow basic question operations
        lenient().when(appPermissionEvaluator.hasPermission(eq(testUser), eq(PermissionName.QUESTION_CREATE)))
                .thenReturn(true);
        lenient().when(appPermissionEvaluator.hasPermission(eq(testUser), eq(PermissionName.QUESTION_UPDATE)))
                .thenReturn(true);
        lenient().when(appPermissionEvaluator.hasPermission(eq(testUser), eq(PermissionName.QUESTION_DELETE)))
                .thenReturn(true);
    }

    private Quiz createTestQuiz(UUID quizId, User creator) {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setCreator(creator);
        quiz.setTitle("Test Quiz");
        quiz.setDescription("Test Description");
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setDifficulty(Difficulty.EASY);
        quiz.setEstimatedTime(30);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setIsDeleted(false);
        return quiz;
    }

    @Test
    @DisplayName("createQuestion: happy path should save and return new UUID")
    void createQuestion_happyPath_savesAndReturnsId() {
        CreateQuestionRequest req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("Q?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));

        when(questionRepository.save(any(Question.class)))
                .thenAnswer(inv -> {
                    Question q = inv.getArgument(0);
                    q.setId(UUID.randomUUID());
                    return q;
                });

        UUID id = questionService.createQuestion(DUMMY_USER, req);

        assertThat(id).isNotNull();
        verify(handler).validateContent(req);
        verify(questionRepository).save(any(Question.class));
    }

    @Test
    @DisplayName("createQuestion: missing quiz should throw ResourceNotFoundException")
    void createQuestion_missingQuiz_throws404() {
        UUID badQuiz = UUID.randomUUID();
        var req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("Q?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));
        req.setQuizIds(List.of(badQuiz));

        lenient().when(quizRepository.findAllById(List.of(badQuiz))).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> questionService.createQuestion(DUMMY_USER, req))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(questionRepository, never()).save(any());
    }

    @Test
    @DisplayName("listQuestions: with quizId should delegate to repository")
    void listQuestions_withQuizId_delegatesToRepo() {
        UUID quizId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 10);
        
        // Create a test quiz owned by the test user
        Quiz testQuiz = createTestQuiz(quizId, testUser);
        
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(testQuiz));
        when(questionRepository.findAllByQuizId_Id(eq(quizId), eq(page)))
                .thenReturn(new PageImpl<>(List.of(new Question())));

        Page<QuestionDto> result = questionService.listQuestions(quizId, page, mock(Authentication.class));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("getQuestion: not found should throw ResourceNotFoundException")
    void getQuestion_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(questionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> questionService.getQuestion(id, mock(Authentication.class)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateQuestion: happy path should update and return DTO")
    void updateQuestion_happyPath_updatesAndReturns() {
        UUID id = UUID.randomUUID();
        Question existing = new Question();
        existing.setId(id);

        // Create a test quiz owned by the test user and associate it with the question
        UUID quizId = UUID.randomUUID();
        Quiz testQuiz = createTestQuiz(quizId, testUser);
        existing.setQuizId(List.of(testQuiz));

        UpdateQuestionRequest updateQuestionRequest = new UpdateQuestionRequest();
        updateQuestionRequest.setType(QuestionType.TRUE_FALSE);
        updateQuestionRequest.setDifficulty(Difficulty.EASY);
        updateQuestionRequest.setQuestionText("Question?");
        updateQuestionRequest.setContent(objectMapper.createObjectNode().put("answer", false));

        when(questionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(questionRepository.saveAndFlush(any(Question.class))).thenAnswer(inv -> inv.getArgument(0));

        QuestionDto dto = questionService.updateQuestion(DUMMY_USER, id, updateQuestionRequest);

        assertThat(dto.getId()).isEqualTo(id);
        verify(handler).validateContent(updateQuestionRequest);
        verify(questionRepository).saveAndFlush(existing);
    }

    @Test
    @DisplayName("deleteQuestion: existing should delete without error")
    void deleteQuestion_existing_deletes() {
        UUID id = UUID.randomUUID();
        var q = new Question();
        q.setId(id);

        // Create a test quiz owned by the test user and associate it with the question
        UUID quizId = UUID.randomUUID();
        Quiz testQuiz = createTestQuiz(quizId, testUser);
        q.setQuizId(List.of(testQuiz));

        when(questionRepository.findById(id)).thenReturn(Optional.of(q));

        questionService.deleteQuestion(DUMMY_USER, id);

        verify(questionRepository).delete(q);
    }

    @Test
    @DisplayName("deleteQuestion: missing should throw ResourceNotFoundException")
    void deleteQuestion_notFound_throws404() {
        UUID id = UUID.randomUUID();
        lenient().when(questionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> questionService.deleteQuestion(DUMMY_USER, id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createQuestion: with tags should succeed")
    void createQuestion_withTags_succeeds() {
        UUID tagId = UUID.randomUUID();
        var req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("T/F?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));
        req.setTagIds(List.of(tagId));

        Tag tag = new Tag();
        tag.setId(tagId);
        when(tagRepository.findAllById(List.of(tagId))).thenReturn(List.of(tag));
        when(questionRepository.save(any(Question.class)))
                .thenAnswer(inv -> {
                    Question q = inv.getArgument(0);
                    q.setId(UUID.randomUUID());
                    return q;
                });

        UUID result = questionService.createQuestion(DUMMY_USER, req);

        assertThat(result).isNotNull();
        verify(tagRepository).findAllById(List.of(tagId));
        verify(questionRepository).save(any(Question.class));
    }

    @Test
    @DisplayName("createQuestion: unknown tag should throw ResourceNotFoundException")
    void createQuestion_unknownTag_throws404() {
        UUID badTag = UUID.randomUUID();
        var req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("T/F?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));
        req.setTagIds(List.of(badTag));

        when(tagRepository.findAllById(List.of(badTag))).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> questionService.createQuestion(DUMMY_USER, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag " + badTag);

        verify(questionRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateQuestion: with tags should succeed")
    void updateQuestion_withTags_succeeds() {
        UUID id = UUID.randomUUID(), tagId = UUID.randomUUID();
        var existing = new Question();
        existing.setId(id);

        // Create a test quiz owned by the test user and associate it with the question
        UUID quizId = UUID.randomUUID();
        Quiz testQuiz = createTestQuiz(quizId, testUser);
        existing.setQuizId(List.of(testQuiz));

        when(questionRepository.findById(id)).thenReturn(Optional.of(existing));
        Tag tag = new Tag();
        tag.setId(tagId);
        when(tagRepository.findAllById(List.of(tagId))).thenReturn(List.of(tag));
        when(questionRepository.saveAndFlush(existing)).thenReturn(existing);

        var req = new UpdateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.MEDIUM);
        req.setQuestionText("Changed?");
        req.setContent(objectMapper.createObjectNode().put("answer", false));
        req.setTagIds(List.of(tagId));

        QuestionDto dto = questionService.updateQuestion(DUMMY_USER, id, req);

        assertThat(dto.getId()).isEqualTo(id);
        verify(tagRepository).findAllById(List.of(tagId));
        verify(questionRepository).saveAndFlush(existing);
    }

    @Test
    @DisplayName("updateQuestion: unknown tag should throw ResourceNotFoundException")
    void updateQuestion_unknownTag_throws404() {
        UUID id = UUID.randomUUID(), badTag = UUID.randomUUID();
        var existing = new Question();
        existing.setId(id);

        // Create a test quiz owned by the test user and associate it with the question
        UUID quizId = UUID.randomUUID();
        Quiz testQuiz = createTestQuiz(quizId, testUser);
        existing.setQuizId(List.of(testQuiz));

        when(questionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(tagRepository.findAllById(List.of(badTag))).thenReturn(Collections.emptyList());

        var req = new UpdateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.MEDIUM);
        req.setQuestionText("Changed?");
        req.setContent(objectMapper.createObjectNode().put("answer", false));
        req.setTagIds(List.of(badTag));

        assertThatThrownBy(() -> questionService.updateQuestion(DUMMY_USER, id, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag " + badTag);

        verify(questionRepository, never()).save(any());
    }
}