package uk.gegc.quizmaker.features.quiz.application.command.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.application.validation.QuizPublishValidator;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for QuizPublishingServiceImpl.
 * 
 * <p>Tests verify quiz publishing workflow including:
 * - Access control (owner or moderator required)
 * - Moderation restrictions (PUBLIC quizzes require moderator to publish)
 * - Publishing validation (quiz must meet quality standards)
 * - Status transitions (DRAFT → PUBLISHED, DRAFT → PENDING_REVIEW, etc.)
 * - Edge cases (orphan quizzes, PRIVATE vs PUBLIC visibility)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizPublishingServiceImpl Tests")
class QuizPublishingServiceImplTest {

    @Mock
    private QuizRepository quizRepository;
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private QuizMapper quizMapper;
    
    @Mock
    private AccessPolicy accessPolicy;
    
    @Mock
    private QuizPublishValidator publishValidator;
    
    @InjectMocks
    private QuizPublishingServiceImpl publishingService;
    
    private User regularUser;
    private User moderatorUser;
    private Quiz quiz;
    private Question validQuestion;
    
    @BeforeEach
    void setUp() {
        // Create users
        regularUser = createUser("regularuser", RoleName.ROLE_USER);
        moderatorUser = createUser("moderator", RoleName.ROLE_MODERATOR);
        
        // Create valid question
        validQuestion = new Question();
        validQuestion.setId(UUID.randomUUID());
        validQuestion.setQuestionText("What is 2+2?");
        validQuestion.setType(QuestionType.MCQ_SINGLE);
        validQuestion.setDifficulty(Difficulty.EASY);
        validQuestion.setContent("{\"options\":[{\"id\":\"1\",\"text\":\"4\",\"correct\":true}]}");
        
        // Create quiz
        quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setDescription("Test description");
        quiz.setCreator(regularUser);
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(10);
        quiz.setQuestions(new HashSet<>(Set.of(validQuestion)));
    }
    
    // =============== PUBLISH Tests ===============
    
    @Nested
    @DisplayName("setStatus to PUBLISHED Tests")
    class PublishTests {
        
        @Test
        @DisplayName("Owner publishes PRIVATE quiz - succeeds")
        void owner_publishPrivateQuiz_succeeds() {
            // Given
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            QuizDto result = publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
            verify(publishValidator).ensurePublishable(quiz);
            verify(quizRepository).save(quiz);
        }
        
        @Test
        @DisplayName("Regular user tries to publish PUBLIC quiz - access denied")
        void regularUser_publishPublicQuiz_throwsForbidden() {
            // Given
            quiz.setVisibility(Visibility.PUBLIC);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only moderators can publish PUBLIC quizzes");
            
            verify(publishValidator, never()).ensurePublishable(any());
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Moderator publishes PUBLIC quiz - succeeds")
        void moderator_publishPublicQuiz_succeeds() {
            // Given
            quiz.setVisibility(Visibility.PUBLIC);
            quiz.setCreator(moderatorUser);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            QuizDto result = publishingService.setStatus("moderator", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
            verify(publishValidator).ensurePublishable(quiz);
        }
        
        @Test
        @DisplayName("Publish quiz without questions - validation fails")
        void publishQuiz_withoutQuestions_validationFails() {
            // Given
            quiz.setQuestions(new HashSet<>());
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            doThrow(new IllegalArgumentException("Cannot publish quiz without questions"))
                .when(publishValidator).ensurePublishable(quiz);
            
            // When & Then
            assertThatThrownBy(() -> publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions");
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Other user tries to publish quiz - access denied")
        void otherUser_publishQuiz_throwsForbidden() {
            // Given
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> publishingService.setStatus("otheruser", quiz.getId(), QuizStatus.PUBLISHED))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Owner or elevated permission required");
            
            verify(publishValidator, never()).ensurePublishable(any());
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Moderator publishes another user's PRIVATE quiz - succeeds")
        void moderator_publishOthersPrivateQuiz_succeeds() {
            // Given - quiz owned by regularUser
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            QuizDto result = publishingService.setStatus("moderator", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        }
    }
    
    // =============== Status Transition Tests ===============
    
    @Nested
    @DisplayName("Non-PUBLISHED Status Transitions")
    class NonPublishedStatusTests {
        
        @Test
        @DisplayName("Set status to DRAFT - no validation required")
        void setStatusToDraft_noValidation() {
            // Given
            quiz.setStatus(QuizStatus.PENDING_REVIEW);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.DRAFT);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
            verify(publishValidator, never()).ensurePublishable(any());
        }
        
        @Test
        @DisplayName("Set status to PENDING_REVIEW - no validation required")
        void setStatusToPendingReview_noValidation() {
            // Given
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PENDING_REVIEW);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
            verify(publishValidator, never()).ensurePublishable(any());
        }
        
        @Test
        @DisplayName("Set status to ARCHIVED - no validation required")
        void setStatusToArchived_noValidation() {
            // Given
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.ARCHIVED);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.ARCHIVED);
            verify(publishValidator, never()).ensurePublishable(any());
        }
    }
    
    // =============== Visibility and Moderation Tests ===============
    
    @Nested
    @DisplayName("Visibility and Moderation Rules")
    class VisibilityModerationTests {
        
        @Test
        @DisplayName("Regular user publishes PRIVATE quiz - bypasses moderation restriction")
        void regularUser_publishPrivateQuiz_bypassesModeration() {
            // Given
            quiz.setVisibility(Visibility.PRIVATE);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
            verify(publishValidator).ensurePublishable(quiz);
        }
        
        @Test
        @DisplayName("Admin publishes PUBLIC quiz - succeeds")
        void admin_publishPublicQuiz_succeeds() {
            // Given
            User adminUser = createUser("admin", RoleName.ROLE_ADMIN);
            quiz.setVisibility(Visibility.PUBLIC);
            quiz.setCreator(adminUser);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(adminUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("admin", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        }
        
        @Test
        @DisplayName("Moderator publishes another user's PUBLIC quiz - succeeds")
        void moderator_publishOthersPublicQuiz_succeeds() {
            // Given - quiz owned by regularUser but is PUBLIC
            quiz.setVisibility(Visibility.PUBLIC);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("moderator", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        }
    }
    
    // =============== Validation Integration Tests ===============
    
    @Nested
    @DisplayName("Publishing Validation Integration")
    class ValidationIntegrationTests {
        
        @Test
        @DisplayName("Publish quiz with insufficient estimated time - validation fails")
        void publishQuiz_insufficientTime_validationFails() {
            // Given
            quiz.setEstimatedTime(0);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            doThrow(new IllegalArgumentException("Quiz must have a minimum estimated time of 1 minute(s)"))
                .when(publishValidator).ensurePublishable(quiz);
            
            // When & Then
            assertThatThrownBy(() -> publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum estimated time");
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Publish quiz with invalid question content - validation fails")
        void publishQuiz_invalidQuestionContent_validationFails() {
            // Given
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            doThrow(new IllegalArgumentException("Question 'What is 2+2?' is invalid: Missing correct option"))
                .when(publishValidator).ensurePublishable(quiz);
            
            // When & Then
            assertThatThrownBy(() -> publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question 'What is 2+2?' is invalid");
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Publish valid quiz - validation passes")
        void publishValidQuiz_validationPasses() {
            // Given
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            verify(publishValidator).ensurePublishable(quiz);
            verify(quizRepository).save(quiz);
        }
    }
    
    // =============== Access Control Tests ===============
    
    @Nested
    @DisplayName("Access Control Tests")
    class AccessControlTests {
        
        @Test
        @DisplayName("Owner can change their quiz status")
        void owner_canChangeStatus() {
            // Given
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.ARCHIVED);
            
            // Then
            verify(accessPolicy).requireOwnerOrAny(
                eq(regularUser),
                eq(regularUser.getId()),
                eq(PermissionName.QUIZ_MODERATE),
                eq(PermissionName.QUIZ_ADMIN)
            );
        }
        
        @Test
        @DisplayName("Moderator can change any quiz status")
        void moderator_canChangeAnyQuizStatus() {
            // Given - quiz owned by regularUser
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("moderator", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            verify(accessPolicy).requireOwnerOrAny(
                eq(moderatorUser),
                eq(regularUser.getId()),
                eq(PermissionName.QUIZ_MODERATE),
                eq(PermissionName.QUIZ_ADMIN)
            );
        }
        
        @Test
        @DisplayName("Non-owner without permissions cannot change status")
        void nonOwnerWithoutPermissions_cannotChangeStatus() {
            // Given
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> publishingService.setStatus("otheruser", quiz.getId(), QuizStatus.DRAFT))
                .isInstanceOf(ForbiddenException.class);
            
            verify(quizRepository, never()).save(any());
        }
    }
    
    // =============== Orphan Quiz Tests ===============
    
    @Nested
    @DisplayName("Orphan Quiz (No Creator) Tests")
    class OrphanQuizTests {
        
        @Test
        @DisplayName("Moderator publishes orphan quiz - succeeds")
        void moderator_publishOrphanQuiz_succeeds() {
            // Given
            quiz.setCreator(null);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), eq(null), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("moderator", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
            verify(accessPolicy).requireOwnerOrAny(any(), eq(null), any(), any());
        }
        
        @Test
        @DisplayName("Regular user cannot publish orphan quiz")
        void regularUser_cannotPublishOrphanQuiz() {
            // Given
            quiz.setCreator(null);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), eq(null), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED))
                .isInstanceOf(ForbiddenException.class);
        }
    }
    
    // =============== Resource Not Found Tests ===============
    
    @Nested
    @DisplayName("Resource Not Found Tests")
    class ResourceNotFoundTests {
        
        @Test
        @DisplayName("Set status for non-existent quiz - throws exception")
        void setStatus_nonExistentQuiz_throwsException() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            
            when(quizRepository.findByIdWithQuestions(nonExistentId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> publishingService.setStatus("regularuser", nonExistentId, QuizStatus.PUBLISHED))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Quiz " + nonExistentId + " not found");
            
            verify(accessPolicy, never()).requireOwnerOrAny(any(), any(), any(), any());
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Set status with non-existent user - throws exception")
        void setStatus_nonExistentUser_throwsException() {
            // Given
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("nonexistent")).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> publishingService.setStatus("nonexistent", quiz.getId(), QuizStatus.PUBLISHED))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User nonexistent not found");
            
            verify(quizRepository, never()).save(any());
        }
    }
    
    // =============== Edge Cases Tests ===============
    
    @Nested
    @DisplayName("Edge Cases and Complex Scenarios")
    class EdgeCasesTests {
        
        @Test
        @DisplayName("Transition from PUBLISHED back to DRAFT - no validation")
        void publishedToDraft_noValidation() {
            // Given
            quiz.setStatus(QuizStatus.PUBLISHED);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.DRAFT);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
            verify(publishValidator, never()).ensurePublishable(any());
        }
        
        @Test
        @DisplayName("Transition from ARCHIVED to PUBLISHED - validates")
        void archivedToPublished_validates() {
            // Given
            quiz.setStatus(QuizStatus.ARCHIVED);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
            verify(publishValidator).ensurePublishable(quiz);
        }
        
        @Test
        @DisplayName("Multiple status changes in sequence")
        void multipleStatusChanges_allSucceed() {
            // Given
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When - DRAFT → PENDING_REVIEW
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PENDING_REVIEW);
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
            
            // When - PENDING_REVIEW → DRAFT
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.DRAFT);
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
            
            // When - DRAFT → PUBLISHED
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED);
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
            
            // Then
            verify(publishValidator, times(1)).ensurePublishable(quiz); // Only for PUBLISHED
        }
        
        @Test
        @DisplayName("Set status to same status - still validates if PUBLISHED")
        void setStatusToSame_stillValidatesIfPublished() {
            // Given
            quiz.setStatus(QuizStatus.PUBLISHED);
            
            when(quizRepository.findByIdWithQuestions(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            doNothing().when(publishValidator).ensurePublishable(quiz);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            publishingService.setStatus("regularuser", quiz.getId(), QuizStatus.PUBLISHED);
            
            // Then
            verify(publishValidator).ensurePublishable(quiz);
            verify(quizRepository).save(quiz);
        }
    }
    
    // =============== Helper Methods ===============
    
    private User createUser(String username, RoleName roleName) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setHashedPassword("hashedpassword");
        
        Role role = Role.builder()
            .roleName(roleName.name())
            .build();
        
        Permission permission = Permission.builder()
            .permissionName(PermissionName.QUIZ_CREATE.name())
            .build();
        
        role.setPermissions(Set.of(permission));
        user.setRoles(Set.of(role));
        
        return user;
    }
}

