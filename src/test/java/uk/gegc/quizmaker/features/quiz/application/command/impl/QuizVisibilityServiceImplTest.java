package uk.gegc.quizmaker.features.quiz.application.command.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
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
 * Comprehensive unit tests for QuizVisibilityServiceImpl.
 * 
 * <p>Tests verify visibility management workflow including:
 * - Access control (owner or moderator required)
 * - Moderation restrictions (only moderators can set PUBLIC)
 * - Visibility transitions (PRIVATE ↔ PUBLIC)
 * - Edge cases (orphan quizzes, invalid inputs)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizVisibilityServiceImpl Tests")
class QuizVisibilityServiceImplTest {

    @Mock
    private QuizRepository quizRepository;
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private AccessPolicy accessPolicy;
    
    @Mock
    private QuizMapper quizMapper;
    
    @InjectMocks
    private QuizVisibilityServiceImpl visibilityService;
    
    private User regularUser;
    private User moderatorUser;
    private Quiz quiz;
    
    @BeforeEach
    void setUp() {
        // Create users
        regularUser = createUser("regularuser", RoleName.ROLE_USER);
        moderatorUser = createUser("moderator", RoleName.ROLE_MODERATOR);
        
        // Create quiz
        quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setDescription("Test description");
        quiz.setCreator(regularUser);
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(10);
        quiz.setQuestions(new HashSet<>());
    }
    
    // =============== Set Visibility to PRIVATE Tests ===============
    
    @Nested
    @DisplayName("setVisibility to PRIVATE Tests")
    class SetVisibilityPrivateTests {
        
        @Test
        @DisplayName("Owner sets visibility to PRIVATE - succeeds")
        void owner_setVisibilityPrivate_succeeds() {
            // Given
            quiz.setVisibility(Visibility.PUBLIC);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            QuizDto result = visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PRIVATE);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
            verify(quizRepository).save(quiz);
        }
        
        @Test
        @DisplayName("Other user sets visibility to PRIVATE - access denied")
        void otherUser_setVisibilityPrivate_throwsForbidden() {
            // Given
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> visibilityService.setVisibility("otheruser", quiz.getId(), Visibility.PRIVATE))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Owner or elevated permission required");
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Moderator sets visibility to PRIVATE on any quiz - succeeds")
        void moderator_setVisibilityPrivate_succeeds() {
            // Given - quiz owned by regularUser
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            QuizDto result = visibilityService.setVisibility("moderator", quiz.getId(), Visibility.PRIVATE);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
        }
    }
    
    // =============== Set Visibility to PUBLIC Tests ===============
    
    @Nested
    @DisplayName("setVisibility to PUBLIC Tests")
    class SetVisibilityPublicTests {
        
        @Test
        @DisplayName("Regular user sets visibility to PUBLIC - access denied")
        void regularUser_setVisibilityPublic_throwsForbidden() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PUBLIC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only moderators can set quiz visibility to PUBLIC");
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Moderator sets visibility to PUBLIC - succeeds")
        void moderator_setVisibilityPublic_succeeds() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            QuizDto result = visibilityService.setVisibility("moderator", quiz.getId(), Visibility.PUBLIC);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PUBLIC);
            verify(quizRepository).save(quiz);
        }
        
        @Test
        @DisplayName("Admin sets visibility to PUBLIC - succeeds")
        void admin_setVisibilityPublic_succeeds() {
            // Given
            User adminUser = createUser("admin", RoleName.ROLE_ADMIN);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(adminUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            QuizDto result = visibilityService.setVisibility("admin", quiz.getId(), Visibility.PUBLIC);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PUBLIC);
        }
        
        @Test
        @DisplayName("Owner who is also moderator can set PUBLIC - succeeds")
        void ownerModerator_setVisibilityPublic_succeeds() {
            // Given - regularUser is owner AND has moderator permissions
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true); // Has moderation permissions
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            QuizDto result = visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PUBLIC);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PUBLIC);
        }
    }
    
    // =============== Access Control Tests ===============
    
    @Nested
    @DisplayName("Access Control Tests")
    class AccessControlTests {
        
        @Test
        @DisplayName("Owner can change visibility of their own quiz")
        void owner_canChangeOwnQuizVisibility() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PRIVATE);
            
            // Then
            verify(accessPolicy).requireOwnerOrAny(
                eq(regularUser),
                eq(regularUser.getId()),
                eq(PermissionName.QUIZ_MODERATE),
                eq(PermissionName.QUIZ_ADMIN)
            );
        }
        
        @Test
        @DisplayName("Moderator can change visibility of any quiz")
        void moderator_canChangeAnyQuizVisibility() {
            // Given - quiz owned by regularUser
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("moderator", quiz.getId(), Visibility.PUBLIC);
            
            // Then
            verify(accessPolicy).requireOwnerOrAny(
                eq(moderatorUser),
                eq(regularUser.getId()),
                eq(PermissionName.QUIZ_MODERATE),
                eq(PermissionName.QUIZ_ADMIN)
            );
        }
        
        @Test
        @DisplayName("Non-owner without permissions cannot change visibility")
        void nonOwnerWithoutPermissions_cannotChangeVisibility() {
            // Given
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> visibilityService.setVisibility("otheruser", quiz.getId(), Visibility.PRIVATE))
                .isInstanceOf(ForbiddenException.class);
            
            verify(quizRepository, never()).save(any());
        }
    }
    
    // =============== Orphan Quiz Tests ===============
    
    @Nested
    @DisplayName("Orphan Quiz (No Creator) Tests")
    class OrphanQuizTests {
        
        @Test
        @DisplayName("Moderator sets visibility on orphan quiz - succeeds")
        void moderator_setVisibilityOnOrphanQuiz_succeeds() {
            // Given
            quiz.setCreator(null);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), eq(null), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            QuizDto result = visibilityService.setVisibility("moderator", quiz.getId(), Visibility.PUBLIC);
            
            // Then
            assertThat(result).isNotNull();
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PUBLIC);
            verify(accessPolicy).requireOwnerOrAny(any(), eq(null), any(), any());
        }
        
        @Test
        @DisplayName("Regular user cannot change orphan quiz visibility")
        void regularUser_cannotChangeOrphanQuizVisibility() {
            // Given
            quiz.setCreator(null);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), eq(null), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PRIVATE))
                .isInstanceOf(ForbiddenException.class);
        }
    }
    
    // =============== Resource Not Found Tests ===============
    
    @Nested
    @DisplayName("Resource Not Found Tests")
    class ResourceNotFoundTests {
        
        @Test
        @DisplayName("Set visibility for non-existent quiz - throws exception")
        void setVisibility_nonExistentQuiz_throwsException() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            
            when(quizRepository.findById(nonExistentId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> visibilityService.setVisibility("regularuser", nonExistentId, Visibility.PUBLIC))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Quiz " + nonExistentId + " not found");
            
            verify(accessPolicy, never()).requireOwnerOrAny(any(), any(), any(), any());
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Set visibility with non-existent user - throws exception")
        void setVisibility_nonExistentUser_throwsException() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("nonexistent")).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> visibilityService.setVisibility("nonexistent", quiz.getId(), Visibility.PUBLIC))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User nonexistent not found");
            
            verify(quizRepository, never()).save(any());
        }
    }
    
    // =============== Visibility Transitions Tests ===============
    
    @Nested
    @DisplayName("Visibility Transition Tests")
    class VisibilityTransitionTests {
        
        @Test
        @DisplayName("Transition from PRIVATE to PUBLIC (as moderator) - succeeds")
        void moderator_privateToPublic_succeeds() {
            // Given
            quiz.setVisibility(Visibility.PRIVATE);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("moderator", quiz.getId(), Visibility.PUBLIC);
            
            // Then
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PUBLIC);
        }
        
        @Test
        @DisplayName("Transition from PUBLIC to PRIVATE (as owner) - succeeds")
        void owner_publicToPrivate_succeeds() {
            // Given
            quiz.setVisibility(Visibility.PUBLIC);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PRIVATE);
            
            // Then
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
        }
        
        @Test
        @DisplayName("Setting visibility to same value - still saves and returns")
        void setVisibilityToSame_stillSaves() {
            // Given
            quiz.setVisibility(Visibility.PRIVATE);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PRIVATE);
            
            // Then
            verify(quizRepository).save(quiz);
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
        }
    }
    
    // =============== Moderation Policy Tests ===============
    
    @Nested
    @DisplayName("Moderation Policy Tests")
    class ModerationPolicyTests {
        
        @Test
        @DisplayName("Owner with no moderation permissions blocked from PUBLIC")
        void ownerWithoutModeration_cannotSetPublic() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PUBLIC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only moderators can set quiz visibility to PUBLIC");
        }
        
        @Test
        @DisplayName("Non-owner moderator can set PUBLIC on someone else's quiz")
        void nonOwnerModerator_canSetPublic() {
            // Given - quiz owned by regularUser, moderator accessing
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("moderator", quiz.getId(), Visibility.PUBLIC);
            
            // Then
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PUBLIC);
        }
        
        @Test
        @DisplayName("Moderator can set PRIVATE (any visibility change allowed)")
        void moderator_canSetPrivate() {
            // Given
            quiz.setVisibility(Visibility.PUBLIC);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("moderator", quiz.getId(), Visibility.PRIVATE);
            
            // Then
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
        }
    }
    
    // =============== Edge Cases Tests ===============
    
    @Nested
    @DisplayName("Edge Cases and Complex Scenarios")
    class EdgeCasesTests {
        
        @Test
        @DisplayName("Multiple visibility changes in sequence")
        void multipleVisibilityChanges_allSucceed() {
            // Given
            quiz.setVisibility(Visibility.PRIVATE);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When - PRIVATE → PUBLIC
            visibilityService.setVisibility("moderator", quiz.getId(), Visibility.PUBLIC);
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PUBLIC);
            
            // When - PUBLIC → PRIVATE
            visibilityService.setVisibility("moderator", quiz.getId(), Visibility.PRIVATE);
            assertThat(quiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
            
            // Then
            verify(quizRepository, times(2)).save(quiz);
        }
        
        @Test
        @DisplayName("Visibility change saves quiz entity")
        void visibilityChange_savesQuizEntity() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PRIVATE);
            
            // Then
            ArgumentCaptor<Quiz> quizCaptor = ArgumentCaptor.forClass(Quiz.class);
            verify(quizRepository).save(quizCaptor.capture());
            assertThat(quizCaptor.getValue().getVisibility()).isEqualTo(Visibility.PRIVATE);
        }
        
        @Test
        @DisplayName("Username lookup tries username then email")
        void usernameLookup_triesUsernameAndEmail() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("user@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("user@example.com", quiz.getId(), Visibility.PRIVATE);
            
            // Then
            verify(userRepository).findByUsername("user@example.com");
            verify(userRepository).findByEmail("user@example.com");
        }
        
        @Test
        @DisplayName("Regular user attempts PUBLIC on PUBLISHED quiz - still forbidden")
        void regularUser_setPublicOnPublished_forbidden() {
            // Given
            quiz.setStatus(QuizStatus.PUBLISHED);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            
            // When & Then
            assertThatThrownBy(() -> visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PUBLIC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only moderators can set quiz visibility to PUBLIC");
            
            verify(quizRepository, never()).save(any());
        }
    }
    
    // =============== Integration with AccessPolicy Tests ===============
    
    @Nested
    @DisplayName("AccessPolicy Integration Tests")
    class AccessPolicyIntegrationTests {
        
        @Test
        @DisplayName("Access policy is invoked before moderation check")
        void accessPolicy_invokedFirst() {
            // Given
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> visibilityService.setVisibility("otheruser", quiz.getId(), Visibility.PRIVATE))
                .isInstanceOf(ForbiddenException.class);
            
            // Verify hasAny was never called (failed at access policy)
            verify(accessPolicy, never()).hasAny(any(), any(), any());
        }
        
        @Test
        @DisplayName("Access policy checks correct permissions")
        void accessPolicy_checksCorrectPermissions() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizRepository.save(quiz)).thenReturn(quiz);
            when(quizMapper.toDto(quiz)).thenReturn(mock(QuizDto.class));
            
            // When
            visibilityService.setVisibility("regularuser", quiz.getId(), Visibility.PRIVATE);
            
            // Then
            verify(accessPolicy).requireOwnerOrAny(
                eq(regularUser),
                eq(regularUser.getId()),
                eq(PermissionName.QUIZ_MODERATE),
                eq(PermissionName.QUIZ_ADMIN)
            );
            verify(accessPolicy).hasAny(
                eq(regularUser),
                eq(PermissionName.QUIZ_MODERATE),
                eq(PermissionName.QUIZ_ADMIN)
            );
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

