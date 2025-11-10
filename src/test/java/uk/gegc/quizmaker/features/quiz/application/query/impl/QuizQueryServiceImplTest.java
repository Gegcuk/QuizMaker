package uk.gegc.quizmaker.features.quiz.application.query.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationStatus;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSearchCriteria;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository; 
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for QuizQueryServiceImpl.
 * 
 * <p>Tests cover:
 * - All query methods with different authentication states
 * - All scope variations (public/me/all)
 * - Generation job queries
 * - Edge cases and error conditions
 * - Access control logic
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizQueryServiceImpl Tests")
class QuizQueryServiceImplTest {

    @Mock
    private QuizRepository quizRepository;
    
    @Mock
    private QuestionRepository questionRepository;
    
    @Mock
    private QuizMapper quizMapper;
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;
    
    @Mock
    private QuizGenerationJobService jobService;
    
    @Mock
    private FeatureFlags featureFlags;
    
    @Mock
    private Authentication authentication;
    
    @InjectMocks
    private QuizQueryServiceImpl queryService;
    
    private User testUser;
    private User moderatorUser;
    private Quiz publicQuiz;
    private Quiz privateQuiz;
    private QuizDto quizDto;
    private Pageable pageable;
    
    @BeforeEach
    void setUp() {
        testUser = createUser("testuser", UUID.randomUUID());
        moderatorUser = createUser("moderator", UUID.randomUUID());
        
        publicQuiz = createQuiz(UUID.randomUUID(), "Public Quiz", testUser, Visibility.PUBLIC, QuizStatus.PUBLISHED);
        privateQuiz = createQuiz(UUID.randomUUID(), "Private Quiz", testUser, Visibility.PRIVATE, QuizStatus.DRAFT);
        
        quizDto = mock(QuizDto.class);
        pageable = PageRequest.of(0, 10);
        
        // Setup default mocks for question count queries (lenient for tests that don't use them)
        lenient().when(questionRepository.countByQuizId_Id(any(UUID.class))).thenReturn(5L);
        lenient().when(questionRepository.countQuestionsByQuizIds(any(List.class))).thenReturn(List.of());
    }
    
    // =============== getQuizById Tests ===============
    
    @Nested
    @DisplayName("getQuizById() Tests")
    class GetQuizByIdTests {
        
        @Test
        @DisplayName("Anonymous user accessing public published quiz - succeeds")
        void anonymousUser_publicQuiz_succeeds() {
            // Given
            when(quizRepository.findByIdWithTags(publicQuiz.getId())).thenReturn(Optional.of(publicQuiz));
            when(quizMapper.toDto(eq(publicQuiz), anyInt())).thenReturn(quizDto);
            
            // When
            QuizDto result = queryService.getQuizById(publicQuiz.getId(), null);
            
            // Then
            assertThat(result).isNotNull();
            verify(quizMapper).toDto(eq(publicQuiz), anyInt());
        }
        
        @Test
        @DisplayName("getQuizById fetches question count correctly")
        void getQuizById_fetchesQuestionCount() {
            // Given
            UUID quizId = UUID.randomUUID();
            when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(publicQuiz));
            when(questionRepository.countByQuizId_Id(quizId)).thenReturn(42L);
            when(quizMapper.toDto(eq(publicQuiz), eq(42))).thenReturn(quizDto);
            
            // When
            QuizDto result = queryService.getQuizById(quizId, null);
            
            // Then
            assertThat(result).isNotNull();
            verify(questionRepository).countByQuizId_Id(quizId);
            verify(quizMapper).toDto(eq(publicQuiz), eq(42));
        }
        
        @Test
        @DisplayName("Anonymous user accessing private quiz - forbidden")
        void anonymousUser_privateQuiz_throwsForbidden() {
            // Given
            when(quizRepository.findByIdWithTags(privateQuiz.getId())).thenReturn(Optional.of(privateQuiz));
            
            // When & Then
            assertThatThrownBy(() -> queryService.getQuizById(privateQuiz.getId(), null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied: quiz is not public");
        }
        
        @Test
        @DisplayName("Owner accessing their private quiz - succeeds")
        void owner_privateQuiz_succeeds() {
            // Given
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("testuser");
            when(quizRepository.findByIdWithTags(privateQuiz.getId())).thenReturn(Optional.of(privateQuiz));
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(appPermissionEvaluator.hasPermission(testUser, PermissionName.QUIZ_MODERATE)).thenReturn(false);
            when(appPermissionEvaluator.hasPermission(testUser, PermissionName.QUIZ_ADMIN)).thenReturn(false);
            when(quizMapper.toDto(eq(privateQuiz), anyInt())).thenReturn(quizDto);
            
            // When
            QuizDto result = queryService.getQuizById(privateQuiz.getId(), authentication);
            
            // Then
            assertThat(result).isNotNull();
            verify(quizMapper).toDto(eq(privateQuiz), anyInt());
        }
        
        @Test
        @DisplayName("Non-owner accessing private quiz - forbidden")
        void nonOwner_privateQuiz_throwsForbidden() {
            // Given
            User otherUser = createUser("otheruser", UUID.randomUUID());
            
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("otheruser");
            when(quizRepository.findByIdWithTags(privateQuiz.getId())).thenReturn(Optional.of(privateQuiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            when(appPermissionEvaluator.hasPermission(otherUser, PermissionName.QUIZ_MODERATE)).thenReturn(false);
            when(appPermissionEvaluator.hasPermission(otherUser, PermissionName.QUIZ_ADMIN)).thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> queryService.getQuizById(privateQuiz.getId(), authentication))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied: quiz is not public");
        }
        
        @Test
        @DisplayName("Moderator accessing any private quiz - succeeds")
        void moderator_privateQuiz_succeeds() {
            // Given
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("moderator");
            when(quizRepository.findByIdWithTags(privateQuiz.getId())).thenReturn(Optional.of(privateQuiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            when(appPermissionEvaluator.hasPermission(moderatorUser, PermissionName.QUIZ_MODERATE)).thenReturn(true);
            when(quizMapper.toDto(eq(privateQuiz), anyInt())).thenReturn(quizDto);
            
            // When
            QuizDto result = queryService.getQuizById(privateQuiz.getId(), authentication);
            
            // Then
            assertThat(result).isNotNull();
            verify(quizMapper).toDto(eq(privateQuiz), anyInt());
        }
        
        @Test
        @DisplayName("Admin accessing any private quiz - succeeds")
        void admin_privateQuiz_succeeds() {
            // Given
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("moderator");
            when(quizRepository.findByIdWithTags(privateQuiz.getId())).thenReturn(Optional.of(privateQuiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            when(appPermissionEvaluator.hasPermission(moderatorUser, PermissionName.QUIZ_MODERATE)).thenReturn(false);
            when(appPermissionEvaluator.hasPermission(moderatorUser, PermissionName.QUIZ_ADMIN)).thenReturn(true);
            when(quizMapper.toDto(eq(privateQuiz), anyInt())).thenReturn(quizDto);
            
            // When
            QuizDto result = queryService.getQuizById(privateQuiz.getId(), authentication);
            
            // Then
            assertThat(result).isNotNull();
            verify(quizMapper).toDto(eq(privateQuiz), anyInt());
        }
        
        @Test
        @DisplayName("Quiz not found - throws exception")
        void quizNotFound_throwsException() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            when(quizRepository.findByIdWithTags(nonExistentId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> queryService.getQuizById(nonExistentId, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Quiz " + nonExistentId + " not found");
        }
        
        @Test
        @DisplayName("User found by email instead of username - succeeds")
        void userFoundByEmail_succeeds() {
            // Given
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("test@example.com");
            when(quizRepository.findByIdWithTags(privateQuiz.getId())).thenReturn(Optional.of(privateQuiz));
            when(userRepository.findByUsername("test@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
            when(appPermissionEvaluator.hasPermission(testUser, PermissionName.QUIZ_MODERATE)).thenReturn(false);
            when(appPermissionEvaluator.hasPermission(testUser, PermissionName.QUIZ_ADMIN)).thenReturn(false);
            when(quizMapper.toDto(eq(privateQuiz), anyInt())).thenReturn(quizDto);
            
            // When
            QuizDto result = queryService.getQuizById(privateQuiz.getId(), authentication);
            
            // Then
            assertThat(result).isNotNull();
            verify(userRepository).findByEmail("test@example.com");
        }
        
        @Test
        @DisplayName("User not found but quiz is public - succeeds")
        void userNotFound_publicQuiz_succeeds() {
            // Given
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("unknown");
            when(quizRepository.findByIdWithTags(publicQuiz.getId())).thenReturn(Optional.of(publicQuiz));
            when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("unknown")).thenReturn(Optional.empty());
            when(quizMapper.toDto(eq(publicQuiz), anyInt())).thenReturn(quizDto);
            
            // When
            QuizDto result = queryService.getQuizById(publicQuiz.getId(), authentication);
            
            // Then
            assertThat(result).isNotNull();
        }
        
        @Test
        @DisplayName("Draft public quiz for non-owner - forbidden")
        void draftPublicQuiz_nonOwner_throwsForbidden() {
            // Given
            Quiz draftPublicQuiz = createQuiz(UUID.randomUUID(), "Draft Public", testUser, Visibility.PUBLIC, QuizStatus.DRAFT);
            
            when(quizRepository.findByIdWithTags(draftPublicQuiz.getId())).thenReturn(Optional.of(draftPublicQuiz));
            
            // When & Then
            assertThatThrownBy(() -> queryService.getQuizById(draftPublicQuiz.getId(), null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied: quiz is not public");
        }
    }
    
    // =============== getPublicQuizzes Tests ===============
    
    @Nested
    @DisplayName("getPublicQuizzes() Tests")
    class GetPublicQuizzesTests {
        
        @Test
        @DisplayName("Returns only public published quizzes")
        void returnsOnlyPublicPublishedQuizzes() {
            // Given
            Page<Quiz> quizPage = new PageImpl<>(List.of(publicQuiz));
            when(quizRepository.findAllByVisibilityAndStatus(Visibility.PUBLIC, QuizStatus.PUBLISHED, pageable))
                .thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getPublicQuizzes(pageable);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(quizRepository).findAllByVisibilityAndStatus(Visibility.PUBLIC, QuizStatus.PUBLISHED, pageable);
        }
        
        @Test
        @DisplayName("Returns empty page when no public quizzes exist")
        void noPublicQuizzes_returnsEmptyPage() {
            // Given
            Page<Quiz> emptyPage = Page.empty(pageable);
            when(quizRepository.findAllByVisibilityAndStatus(Visibility.PUBLIC, QuizStatus.PUBLISHED, pageable))
                .thenReturn(emptyPage);
            
            // When
            Page<QuizDto> result = queryService.getPublicQuizzes(pageable);
            
            // Then
            assertThat(result).isEmpty();
        }
        
        @Test
        @DisplayName("Maps all quizzes correctly")
        void mapsAllQuizzesCorrectly() {
            // Given
            Quiz quiz1 = createQuiz(UUID.randomUUID(), "Quiz 1", testUser, Visibility.PUBLIC, QuizStatus.PUBLISHED);
            Quiz quiz2 = createQuiz(UUID.randomUUID(), "Quiz 2", testUser, Visibility.PUBLIC, QuizStatus.PUBLISHED);
            Page<Quiz> quizPage = new PageImpl<>(List.of(quiz1, quiz2));
            
            when(quizRepository.findAllByVisibilityAndStatus(Visibility.PUBLIC, QuizStatus.PUBLISHED, pageable))
                .thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getPublicQuizzes(pageable);
            
            // Then
            assertThat(result.getContent()).hasSize(2);
            verify(quizMapper, times(2)).toDto(any(Quiz.class), anyInt());
        }
        
        @Test
        @DisplayName("Batch fetches question counts with single query (no N+1)")
        void batchFetchesQuestionCounts_singleQuery() {
            // Given
            UUID quiz1Id = UUID.randomUUID();
            UUID quiz2Id = UUID.randomUUID();
            UUID quiz3Id = UUID.randomUUID();
            
            Quiz quiz1 = createQuiz(quiz1Id, "Quiz 1", testUser, Visibility.PUBLIC, QuizStatus.PUBLISHED);
            Quiz quiz2 = createQuiz(quiz2Id, "Quiz 2", testUser, Visibility.PUBLIC, QuizStatus.PUBLISHED);
            Quiz quiz3 = createQuiz(quiz3Id, "Quiz 3", testUser, Visibility.PUBLIC, QuizStatus.PUBLISHED);
            
            Page<Quiz> quizPage = new PageImpl<>(List.of(quiz1, quiz2, quiz3), pageable, 3);
            
            when(quizRepository.findAllByVisibilityAndStatus(Visibility.PUBLIC, QuizStatus.PUBLISHED, pageable))
                .thenReturn(quizPage);
            // Mock batch count query - returns counts for all quizzes
            when(questionRepository.countQuestionsByQuizIds(List.of(quiz1Id, quiz2Id, quiz3Id)))
                .thenReturn(List.of(
                    new Object[]{quiz1Id, 5L},
                    new Object[]{quiz2Id, 10L},
                    new Object[]{quiz3Id, 15L}
                ));
            when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getPublicQuizzes(pageable);
            
            // Then
            assertThat(result.getContent()).hasSize(3);
            
            // CRITICAL: Verify batch query called ONCE (not 3 times = N+1)
            verify(questionRepository, times(1)).countQuestionsByQuizIds(any());
            
            // Verify each quiz mapped with its specific count
            verify(quizMapper).toDto(eq(quiz1), eq(5));
            verify(quizMapper).toDto(eq(quiz2), eq(10));
            verify(quizMapper).toDto(eq(quiz3), eq(15));
        }
        
        @Test
        @DisplayName("Handles missing question counts (quiz with 0 questions)")
        void handlesMissingQuestionCounts() {
            // Given
            UUID quiz1Id = UUID.randomUUID();
            UUID quiz2Id = UUID.randomUUID();
            
            Quiz quiz1 = createQuiz(quiz1Id, "Quiz with questions", testUser, Visibility.PUBLIC, QuizStatus.PUBLISHED);
            Quiz quiz2 = createQuiz(quiz2Id, "Empty quiz", testUser, Visibility.PUBLIC, QuizStatus.PUBLISHED);
            
            Page<Quiz> quizPage = new PageImpl<>(List.of(quiz1, quiz2), pageable, 2);
            
            when(quizRepository.findAllByVisibilityAndStatus(Visibility.PUBLIC, QuizStatus.PUBLISHED, pageable))
                .thenReturn(quizPage);
            // Batch query only returns count for quiz1 (quiz2 has 0 questions)
            List<Object[]> batchResults = new java.util.ArrayList<>();
            batchResults.add(new Object[]{quiz1Id, 7L});
            // quiz2Id not in results = 0 questions
            when(questionRepository.countQuestionsByQuizIds(List.of(quiz1Id, quiz2Id)))
                .thenReturn(batchResults);
            when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getPublicQuizzes(pageable);
            
            // Then
            assertThat(result.getContent()).hasSize(2);
            
            // Verify quiz1 gets its count, quiz2 defaults to 0
            verify(quizMapper).toDto(eq(quiz1), eq(7));
            verify(quizMapper).toDto(eq(quiz2), eq(0)); // Default to 0 when not in map
        }
    }
    
    // =============== getQuizzes Tests ===============
    
    @Nested
    @DisplayName("getQuizzes() with scope='public' Tests")
    class GetQuizzesScopePublicTests {
        
        @Test
        @DisplayName("Anonymous user with scope=public - returns public quizzes")
        void anonymousUser_scopePublic_succeeds() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            Page<Quiz> quizPage = new PageImpl<>(List.of(publicQuiz));
            
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getQuizzes(pageable, criteria, "public", null);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
        }
        
        @Test
        @DisplayName("Authenticated user with scope=public - returns public quizzes")
        void authenticatedUser_scopePublic_succeeds() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            Page<Quiz> quizPage = new PageImpl<>(List.of(publicQuiz));
            
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("testuser");
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getQuizzes(pageable, criteria, "public", authentication);
            
            // Then
            assertThat(result).isNotNull();
        }
        
        @Test
        @DisplayName("Default scope (empty) treated as public")
        void defaultScope_treatedAsPublic() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            Page<Quiz> quizPage = new PageImpl<>(List.of(publicQuiz));
            
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getQuizzes(pageable, criteria, "invalid", null);
            
            // Then
            assertThat(result).isNotNull();
        }
    }
    
    @Nested
    @DisplayName("getQuizzes() with scope='me' Tests")
    class GetQuizzesScopeMeTests {
        
        @Test
        @DisplayName("Authenticated user with scope=me - returns their quizzes")
        void authenticatedUser_scopeMe_succeeds() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            Page<Quiz> quizPage = new PageImpl<>(List.of(privateQuiz));
            
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("testuser");
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getQuizzes(pageable, criteria, "me", authentication);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
        }
        
        @Test
        @DisplayName("Anonymous user with scope=me - throws forbidden")
        void anonymousUser_scopeMe_throwsForbidden() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            // When & Then
            assertThatThrownBy(() -> queryService.getQuizzes(pageable, criteria, "me", null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Authentication required for scope=me");
        }
        
        @Test
        @DisplayName("User found by email with scope=me - succeeds")
        void userFoundByEmail_scopeMe_succeeds() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            Page<Quiz> quizPage = new PageImpl<>(List.of(privateQuiz));
            
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("test@example.com");
            when(userRepository.findByUsername("test@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getQuizzes(pageable, criteria, "me", authentication);
            
            // Then
            assertThat(result).isNotNull();
            verify(userRepository).findByEmail("test@example.com");
        }
        
        @Test
        @DisplayName("Case insensitive scope=ME - succeeds")
        void caseInsensitiveScope_succeeds() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            Page<Quiz> quizPage = new PageImpl<>(List.of(privateQuiz));
            
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("testuser");
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getQuizzes(pageable, criteria, "ME", authentication);
            
            // Then
            assertThat(result).isNotNull();
        }
    }
    
    @Nested
    @DisplayName("getQuizzes() with scope='all' Tests")
    class GetQuizzesScopeAllTests {
        
        @Test
        @DisplayName("Moderator with scope=all - returns all quizzes")
        void moderator_scopeAll_succeeds() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            Page<Quiz> quizPage = new PageImpl<>(List.of(publicQuiz, privateQuiz));
            
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("moderator");
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            when(appPermissionEvaluator.hasPermission(moderatorUser, PermissionName.QUIZ_MODERATE)).thenReturn(true);
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getQuizzes(pageable, criteria, "all", authentication);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(2);
        }
        
        @Test
        @DisplayName("Admin with scope=all - returns all quizzes")
        void admin_scopeAll_succeeds() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            Page<Quiz> quizPage = new PageImpl<>(List.of(publicQuiz, privateQuiz));
            
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("moderator");
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            when(appPermissionEvaluator.hasPermission(moderatorUser, PermissionName.QUIZ_MODERATE)).thenReturn(false);
            when(appPermissionEvaluator.hasPermission(moderatorUser, PermissionName.QUIZ_ADMIN)).thenReturn(true);
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getQuizzes(pageable, criteria, "all", authentication);
            
            // Then
            assertThat(result).isNotNull();
        }
        
        @Test
        @DisplayName("Regular user with scope=all - throws forbidden")
        void regularUser_scopeAll_throwsForbidden() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("testuser");
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
            when(appPermissionEvaluator.hasPermission(testUser, PermissionName.QUIZ_MODERATE)).thenReturn(false);
            when(appPermissionEvaluator.hasPermission(testUser, PermissionName.QUIZ_ADMIN)).thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> queryService.getQuizzes(pageable, criteria, "all", authentication))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Moderator/Admin permissions required for scope=all");
        }
        
        @Test
        @DisplayName("Anonymous user with scope=all - throws forbidden")
        void anonymousUser_scopeAll_throwsForbidden() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            
            // When & Then
            assertThatThrownBy(() -> queryService.getQuizzes(pageable, criteria, "all", null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Moderator/Admin permissions required for scope=all");
        }
        
        @Test
        @DisplayName("Case insensitive scope=ALL - succeeds")
        void caseInsensitiveScope_succeeds() {
            // Given
            QuizSearchCriteria criteria = new QuizSearchCriteria(null, null, null, null, null);
            Page<Quiz> quizPage = new PageImpl<>(List.of(publicQuiz));
            
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getName()).thenReturn("moderator");
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            when(appPermissionEvaluator.hasPermission(moderatorUser, PermissionName.QUIZ_MODERATE)).thenReturn(true);
            when(quizRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(quizPage);
                    when(quizMapper.toDto(any(Quiz.class), anyInt())).thenReturn(quizDto);
            
            // When
            Page<QuizDto> result = queryService.getQuizzes(pageable, criteria, "ALL", authentication);
            
            // Then
            assertThat(result).isNotNull();
        }
    }
    
    // =============== Generation Job Tests ===============
    
    @Nested
    @DisplayName("getGenerationStatus() Tests")
    class GetGenerationStatusTests {
        
        @Test
        @DisplayName("Valid job - returns status")
        void validJob_returnsStatus() {
            // Given
            UUID jobId = UUID.randomUUID();
            QuizGenerationJob job = createJob(jobId, testUser, GenerationStatus.PROCESSING);
            
            when(jobService.getJobByIdAndUsername(jobId, "testuser")).thenReturn(job);
            when(featureFlags.isBilling()).thenReturn(true);
            
            // When
            QuizGenerationStatus result = queryService.getGenerationStatus(jobId, "testuser");
            
            // Then
            assertThat(result).isNotNull();
            verify(jobService).getJobByIdAndUsername(jobId, "testuser");
            verify(featureFlags).isBilling();
        }
        
        @Test
        @DisplayName("Job not found - throws exception")
        void jobNotFound_throwsException() {
            // Given
            UUID jobId = UUID.randomUUID();
            
            when(jobService.getJobByIdAndUsername(jobId, "testuser"))
                .thenThrow(new ResourceNotFoundException("Job not found"));
            
            // When & Then
            assertThatThrownBy(() -> queryService.getGenerationStatus(jobId, "testuser"))
                .isInstanceOf(ResourceNotFoundException.class);
        }
        
        @Test
        @DisplayName("Wrong owner - throws exception")
        void wrongOwner_throwsException() {
            // Given
            UUID jobId = UUID.randomUUID();
            
            when(jobService.getJobByIdAndUsername(jobId, "otheruser"))
                .thenThrow(new ForbiddenException("Access denied"));
            
            // When & Then
            assertThatThrownBy(() -> queryService.getGenerationStatus(jobId, "otheruser"))
                .isInstanceOf(ForbiddenException.class);
        }
        
        @Test
        @DisplayName("Billing disabled - returns status without billing info")
        void billingDisabled_returnsStatusWithoutBilling() {
            // Given
            UUID jobId = UUID.randomUUID();
            QuizGenerationJob job = createJob(jobId, testUser, GenerationStatus.COMPLETED);
            
            when(jobService.getJobByIdAndUsername(jobId, "testuser")).thenReturn(job);
            when(featureFlags.isBilling()).thenReturn(false);
            
            // When
            QuizGenerationStatus result = queryService.getGenerationStatus(jobId, "testuser");
            
            // Then
            assertThat(result).isNotNull();
            verify(featureFlags).isBilling();
        }
    }
    
    @Nested
    @DisplayName("getGeneratedQuiz() Tests")
    class GetGeneratedQuizTests {
        
        @Test
        @DisplayName("Completed job with quiz - returns quiz")
        void completedJob_returnsQuiz() {
            // Given
            UUID jobId = UUID.randomUUID();
            UUID quizId = UUID.randomUUID();
            QuizGenerationJob job = createJob(jobId, testUser, GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(quizId);
            Quiz generatedQuiz = createQuiz(quizId, "Generated Quiz", testUser, Visibility.PRIVATE, QuizStatus.DRAFT);
            
            when(jobService.getJobByIdAndUsername(jobId, "testuser")).thenReturn(job);
            when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(generatedQuiz));
            when(quizMapper.toDto(eq(generatedQuiz), anyInt())).thenReturn(quizDto);
            
            // When
            QuizDto result = queryService.getGeneratedQuiz(jobId, "testuser");
            
            // Then
            assertThat(result).isNotNull();
            verify(quizMapper).toDto(eq(generatedQuiz), anyInt());
        }
        
        @Test
        @DisplayName("Job not completed - throws validation exception")
        void jobNotCompleted_throwsValidationException() {
            // Given
            UUID jobId = UUID.randomUUID();
            QuizGenerationJob job = createJob(jobId, testUser, GenerationStatus.PROCESSING);
            
            when(jobService.getJobByIdAndUsername(jobId, "testuser")).thenReturn(job);
            
            // When & Then
            assertThatThrownBy(() -> queryService.getGeneratedQuiz(jobId, "testuser"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Generation job is not yet completed");
        }
        
        @Test
        @DisplayName("Completed job without quiz ID - throws resource not found")
        void completedJobNoQuizId_throwsResourceNotFound() {
            // Given
            UUID jobId = UUID.randomUUID();
            QuizGenerationJob job = createJob(jobId, testUser, GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(null);
            
            when(jobService.getJobByIdAndUsername(jobId, "testuser")).thenReturn(job);
            
            // When & Then
            assertThatThrownBy(() -> queryService.getGeneratedQuiz(jobId, "testuser"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Generated quiz not found for job");
        }
        
        @Test
        @DisplayName("Quiz not found in repository - throws exception")
        void quizNotFound_throwsException() {
            // Given
            UUID jobId = UUID.randomUUID();
            UUID quizId = UUID.randomUUID();
            QuizGenerationJob job = createJob(jobId, testUser, GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(quizId);
            
            when(jobService.getJobByIdAndUsername(jobId, "testuser")).thenReturn(job);
            when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> queryService.getGeneratedQuiz(jobId, "testuser"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Quiz " + quizId + " not found");
        }
        
        @Test
        @DisplayName("Quiz belongs to different user - throws forbidden")
        void quizBelongsToDifferentUser_throwsForbidden() {
            // Given
            UUID jobId = UUID.randomUUID();
            UUID quizId = UUID.randomUUID();
            User otherUser = createUser("otheruser", UUID.randomUUID());
            QuizGenerationJob job = createJob(jobId, testUser, GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(quizId);
            Quiz generatedQuiz = createQuiz(quizId, "Generated Quiz", otherUser, Visibility.PRIVATE, QuizStatus.DRAFT);
            
            when(jobService.getJobByIdAndUsername(jobId, "testuser")).thenReturn(job);
            when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(generatedQuiz));
            
            // When & Then
            assertThatThrownBy(() -> queryService.getGeneratedQuiz(jobId, "testuser"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");
        }
        
        @Test
        @DisplayName("Quiz has no creator - throws forbidden")
        void quizNoCreator_throwsForbidden() {
            // Given
            UUID jobId = UUID.randomUUID();
            UUID quizId = UUID.randomUUID();
            QuizGenerationJob job = createJob(jobId, testUser, GenerationStatus.COMPLETED);
            job.setGeneratedQuizId(quizId);
            Quiz generatedQuiz = createQuiz(quizId, "Generated Quiz", null, Visibility.PRIVATE, QuizStatus.DRAFT);
            
            when(jobService.getJobByIdAndUsername(jobId, "testuser")).thenReturn(job);
            when(quizRepository.findByIdWithTags(quizId)).thenReturn(Optional.of(generatedQuiz));
            
            // When & Then
            assertThatThrownBy(() -> queryService.getGeneratedQuiz(jobId, "testuser"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");
        }
    }
    
    @Nested
    @DisplayName("getGenerationJobs() Tests")
    class GetGenerationJobsTests {
        
        @Test
        @DisplayName("User with jobs - returns page of statuses")
        void userWithJobs_returnsStatuses() {
            // Given
            QuizGenerationJob job1 = createJob(UUID.randomUUID(), testUser, GenerationStatus.COMPLETED);
            QuizGenerationJob job2 = createJob(UUID.randomUUID(), testUser, GenerationStatus.PROCESSING);
            Page<QuizGenerationJob> jobPage = new PageImpl<>(List.of(job1, job2));
            
            when(jobService.getJobsByUser("testuser", pageable)).thenReturn(jobPage);
            when(featureFlags.isBilling()).thenReturn(true);
            
            // When
            Page<QuizGenerationStatus> result = queryService.getGenerationJobs("testuser", pageable);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(2);
        }
        
        @Test
        @DisplayName("User with no jobs - returns empty page")
        void userWithNoJobs_returnsEmptyPage() {
            // Given
            Page<QuizGenerationJob> emptyPage = Page.empty(pageable);
            
            when(jobService.getJobsByUser("testuser", pageable)).thenReturn(emptyPage);
            
            // When
            Page<QuizGenerationStatus> result = queryService.getGenerationJobs("testuser", pageable);
            
            // Then
            assertThat(result).isEmpty();
        }
        
        @Test
        @DisplayName("Maps all jobs correctly")
        void mapsAllJobsCorrectly() {
            // Given
            QuizGenerationJob job1 = createJob(UUID.randomUUID(), testUser, GenerationStatus.COMPLETED);
            QuizGenerationJob job2 = createJob(UUID.randomUUID(), testUser, GenerationStatus.FAILED);
            QuizGenerationJob job3 = createJob(UUID.randomUUID(), testUser, GenerationStatus.PROCESSING);
            Page<QuizGenerationJob> jobPage = new PageImpl<>(List.of(job1, job2, job3));
            
            when(jobService.getJobsByUser("testuser", pageable)).thenReturn(jobPage);
            when(featureFlags.isBilling()).thenReturn(false);
            
            // When
            Page<QuizGenerationStatus> result = queryService.getGenerationJobs("testuser", pageable);
            
            // Then
            assertThat(result.getContent()).hasSize(3);
        }
    }
    
    @Nested
    @DisplayName("getGenerationJobStatistics() Tests")
    class GetGenerationJobStatisticsTests {
        
        @Test
        @DisplayName("Returns statistics for user")
        void returnsStatistics() {
            // Given
            QuizGenerationJobService.JobStatistics stats = mock(QuizGenerationJobService.JobStatistics.class);
            
            when(jobService.getJobStatistics("testuser")).thenReturn(stats);
            
            // When
            QuizGenerationJobService.JobStatistics result = queryService.getGenerationJobStatistics("testuser");
            
            // Then
            assertThat(result).isNotNull();
            assertThat(result).isEqualTo(stats);
            verify(jobService).getJobStatistics("testuser");
        }
        
        @Test
        @DisplayName("User not found - throws exception")
        void userNotFound_throwsException() {
            // Given
            when(jobService.getJobStatistics("nonexistent"))
                .thenThrow(new ResourceNotFoundException("User not found"));
            
            // When & Then
            assertThatThrownBy(() -> queryService.getGenerationJobStatistics("nonexistent"))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }
    
    // =============== Helper Methods ===============
    
    private User createUser(String username, UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        return user;
    }
    
    private Quiz createQuiz(UUID id, String title, User creator, Visibility visibility, QuizStatus status) {
        Quiz quiz = new Quiz();
        quiz.setId(id);
        quiz.setTitle(title);
        quiz.setCreator(creator);
        quiz.setVisibility(visibility);
        quiz.setStatus(status);
        return quiz;
    }
    
    private QuizGenerationJob createJob(UUID jobId, User user, GenerationStatus status) {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setUser(user);
        job.setStatus(status);
        return job;
    }
}

