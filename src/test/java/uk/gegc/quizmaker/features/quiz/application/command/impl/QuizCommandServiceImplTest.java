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
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.api.dto.*;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for QuizCommandServiceImpl.
 * 
 * <p>Tests verify all command operations (create, update, delete) including:
 * - Access control enforcement via AccessPolicy
 * - Category resolution and fallback to defaults
 * - Tag association and validation
 * - Quiz status transitions and moderation workflows
 * - Content hash tracking and auto-transition to PENDING_REVIEW
 * - Bulk operations and error handling
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizCommandServiceImpl Tests")
class QuizCommandServiceImplTest {

    private static final UUID DEFAULT_CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private TagRepository tagRepository;
    
    @Mock
    private QuizMapper quizMapper;
    
    @Mock
    private AccessPolicy accessPolicy;
    
    @Mock
    private QuizRepository quizRepository;
    
    @Mock
    private CategoryRepository categoryRepository;
    
    @Mock
    private QuizDefaultsProperties quizDefaultsProperties;
    
    @Mock
    private QuizHashCalculator quizHashCalculator;
    
    @InjectMocks
    private QuizCommandServiceImpl quizCommandService;
    
    private User regularUser;
    private User moderatorUser;
    private Category defaultCategory;
    private Category customCategory;
    private Tag tag1;
    private Tag tag2;
    private Quiz quiz;
    
    @BeforeEach
    void setUp() {
        // Create test users
        regularUser = createUser("regularuser", RoleName.ROLE_USER);
        moderatorUser = createUser("moderator", RoleName.ROLE_MODERATOR);
        
        // Create categories
        defaultCategory = new Category();
        defaultCategory.setId(DEFAULT_CATEGORY_ID);
        defaultCategory.setName("Default Category");
        
        customCategory = new Category();
        customCategory.setId(UUID.randomUUID());
        customCategory.setName("Custom Category");
        
        // Create tags
        tag1 = new Tag();
        tag1.setId(UUID.randomUUID());
        tag1.setName("Tag 1");
        
        tag2 = new Tag();
        tag2.setId(UUID.randomUUID());
        tag2.setName("Tag 2");
        
        // Create quiz
        quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setDescription("Test description");
        quiz.setCreator(regularUser);
        quiz.setCategory(defaultCategory);
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(10);
        quiz.setQuestions(new HashSet<>());
        quiz.setTags(new HashSet<>());
        
        // Setup common mocks
        lenient().when(quizDefaultsProperties.getDefaultCategoryId()).thenReturn(DEFAULT_CATEGORY_ID);
        lenient().when(categoryRepository.findById(DEFAULT_CATEGORY_ID)).thenReturn(Optional.of(defaultCategory));
    }
    
    // =============== CREATE QUIZ Tests ===============
    
    @Nested
    @DisplayName("createQuiz Tests")
    class CreateQuizTests {
        
        @Test
        @DisplayName("Regular user creates quiz - forces PRIVATE/DRAFT")
        void regularUser_createQuiz_forcesPrivateDraft() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "My Quiz", "Description",
                Visibility.PUBLIC, // Try to create PUBLIC
                Difficulty.MEDIUM,
                false, false,
                10, 5,
                customCategory.getId(),
                List.of(tag1.getId())
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(categoryRepository.findById(customCategory.getId())).thenReturn(Optional.of(customCategory));
            when(tagRepository.findById(tag1.getId())).thenReturn(Optional.of(tag1));
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
            when(quizMapper.toEntity(any(), any(), any(), any())).thenReturn(quiz);
            when(quizRepository.save(any(Quiz.class))).thenReturn(quiz);
            
            // When
            UUID quizId = quizCommandService.createQuiz("regularuser", request);
            
            // Then
            assertThat(quizId).isEqualTo(quiz.getId());
            
            ArgumentCaptor<Quiz> quizCaptor = ArgumentCaptor.forClass(Quiz.class);
            verify(quizRepository).save(quizCaptor.capture());
            Quiz savedQuiz = quizCaptor.getValue();
            
            assertThat(savedQuiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
            assertThat(savedQuiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        }
        
        @Test
        @DisplayName("Moderator creates PUBLIC quiz - auto-sets to PUBLISHED")
        void moderator_createPublicQuiz_autoSetsPublished() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "Public Quiz", "Description",
                Visibility.PUBLIC,
                Difficulty.EASY,
                false, false,
                10, 5,
                customCategory.getId(),
                List.of()
            );
            
            Quiz publicQuiz = new Quiz();
            publicQuiz.setId(UUID.randomUUID());
            publicQuiz.setTitle("Public Quiz");
            publicQuiz.setVisibility(Visibility.PUBLIC);
            publicQuiz.setStatus(QuizStatus.DRAFT); // Initial status
            
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            when(categoryRepository.findById(customCategory.getId())).thenReturn(Optional.of(customCategory));
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizMapper.toEntity(any(), any(), any(), any())).thenReturn(publicQuiz);
            when(quizRepository.save(any(Quiz.class))).thenReturn(publicQuiz);
            
            // When
            UUID quizId = quizCommandService.createQuiz("moderator", request);
            
            // Then
            ArgumentCaptor<Quiz> quizCaptor = ArgumentCaptor.forClass(Quiz.class);
            verify(quizRepository).save(quizCaptor.capture());
            Quiz savedQuiz = quizCaptor.getValue();
            
            // PUBLIC quizzes are forced to PUBLISHED
            assertThat(savedQuiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        }
        
        @Test
        @DisplayName("Moderator creates PRIVATE quiz - preserves requested status")
        void moderator_createPrivateQuiz_preservesStatus() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "Private Quiz", "Description",
                Visibility.PRIVATE,
                Difficulty.MEDIUM,
                false, false,
                10, 5,
                null, // Use default category
                List.of()
            );
            
            Quiz privateQuiz = new Quiz();
            privateQuiz.setId(UUID.randomUUID());
            privateQuiz.setVisibility(Visibility.PRIVATE);
            privateQuiz.setStatus(QuizStatus.DRAFT);
            
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            when(accessPolicy.hasAny(moderatorUser, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
            when(quizMapper.toEntity(any(), any(), any(), any())).thenReturn(privateQuiz);
            when(quizRepository.save(any(Quiz.class))).thenReturn(privateQuiz);
            
            // When
            quizCommandService.createQuiz("moderator", request);
            
            // Then
            ArgumentCaptor<Quiz> quizCaptor = ArgumentCaptor.forClass(Quiz.class);
            verify(quizRepository).save(quizCaptor.capture());
            Quiz savedQuiz = quizCaptor.getValue();
            
            // PRIVATE quizzes preserve their status
            assertThat(savedQuiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
            assertThat(savedQuiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        }
        
        @Test
        @DisplayName("Create quiz with null categoryId uses default category")
        void createQuiz_withNullCategory_usesDefault() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                null, // Null category
                List.of()
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizMapper.toEntity(any(), any(), eq(defaultCategory), any())).thenReturn(quiz);
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.createQuiz("regularuser", request);
            
            // Then
            verify(quizMapper).toEntity(any(), any(), eq(defaultCategory), any());
        }
        
        @Test
        @DisplayName("Create quiz with invalid categoryId falls back to default")
        void createQuiz_withInvalidCategory_fallsBackToDefault() {
            // Given
            UUID invalidCategoryId = UUID.randomUUID();
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                invalidCategoryId,
                List.of()
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(categoryRepository.findById(invalidCategoryId)).thenReturn(Optional.empty());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizMapper.toEntity(any(), any(), eq(defaultCategory), any())).thenReturn(quiz);
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.createQuiz("regularuser", request);
            
            // Then
            verify(quizMapper).toEntity(any(), any(), eq(defaultCategory), any());
        }
        
        @Test
        @DisplayName("Create quiz with tags associates all tags")
        void createQuiz_withTags_associatesTags() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                null,
                List.of(tag1.getId(), tag2.getId())
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(tagRepository.findById(tag1.getId())).thenReturn(Optional.of(tag1));
            when(tagRepository.findById(tag2.getId())).thenReturn(Optional.of(tag2));
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizMapper.toEntity(any(), any(), any(), any())).thenReturn(quiz);
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.createQuiz("regularuser", request);
            
            // Then
            ArgumentCaptor<Set<Tag>> tagsCaptor = ArgumentCaptor.forClass(Set.class);
            verify(quizMapper).toEntity(any(), any(), any(), tagsCaptor.capture());
            Set<Tag> capturedTags = tagsCaptor.getValue();
            
            assertThat(capturedTags).containsExactlyInAnyOrder(tag1, tag2);
        }
        
        @Test
        @DisplayName("Create quiz with non-existent tag throws exception")
        void createQuiz_withNonExistentTag_throwsException() {
            // Given
            UUID invalidTagId = UUID.randomUUID();
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                null,
                List.of(invalidTagId)
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(tagRepository.findById(invalidTagId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.createQuiz("regularuser", request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag " + invalidTagId + " not found");
        }
        
        @Test
        @DisplayName("Create quiz with non-existent user throws exception")
        void createQuiz_withNonExistentUser_throwsException() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                null, List.of()
            );
            
            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("nonexistent")).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.createQuiz("nonexistent", request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User nonexistent not found");
        }
        
        @Test
        @DisplayName("Create quiz with missing default category throws IllegalStateException")
        void createQuiz_withMissingDefaultCategory_throwsException() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                null, List.of()
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(categoryRepository.findById(DEFAULT_CATEGORY_ID)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.createQuiz("regularuser", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configured default category");
        }
    }
    
    // =============== UPDATE QUIZ Tests ===============
    
    @Nested
    @DisplayName("updateQuiz Tests")
    class UpdateQuizTests {
        
        @Test
        @DisplayName("Owner updates their own quiz - succeeds")
        void owner_updateOwnQuiz_succeeds() {
            // Given
            UpdateQuizRequest request = new UpdateQuizRequest(
                "Updated Title",
                "Updated description",
                null, null, null, null, null, null, null, null
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("hash123");
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash123");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            QuizDto result = quizCommandService.updateQuiz("regularuser", quiz.getId(), request);
            
            // Then
            assertThat(result).isNotNull();
            verify(accessPolicy).requireOwnerOrAny(
                eq(regularUser),
                eq(regularUser.getId()),
                eq(PermissionName.QUIZ_MODERATE),
                eq(PermissionName.QUIZ_ADMIN)
            );
            verify(quizMapper).updateEntity(eq(quiz), eq(request), any(), any());
        }
        
        @Test
        @DisplayName("Other user updates quiz - access denied")
        void otherUser_updateQuiz_throwsForbidden() {
            // Given
            UpdateQuizRequest request = new UpdateQuizRequest(
                "Malicious Update", null, null, null, null, null, null, null, null, null
            );
            
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.updateQuiz("otheruser", quiz.getId(), request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Owner or elevated permission required");
            
            verify(quizMapper, never()).updateEntity(any(), any(), any(), any());
        }
        
        @Test
        @DisplayName("Update PENDING_REVIEW quiz - auto-reverts to DRAFT")
        void updatePendingReviewQuiz_autoRevertsToDraft() {
            // Given
            quiz.setStatus(QuizStatus.PENDING_REVIEW);
            
            UpdateQuizRequest request = new UpdateQuizRequest(
                "Updated Title", null, null, null, null, null, null, null, null, null
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("hash123");
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash123");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.updateQuiz("regularuser", quiz.getId(), request);
            
            // Then
            assertThat(quiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        }
        
        @Test
        @DisplayName("Update PUBLISHED quiz with content change - transitions to PENDING_REVIEW")
        void updatePublishedQuiz_contentChanged_transitionsToPendingReview() {
            // Given
            quiz.setStatus(QuizStatus.PUBLISHED);
            quiz.setContentHash("oldHash");
            quiz.setReviewedAt(Instant.now());
            quiz.setReviewedBy(moderatorUser);
            quiz.setRejectionReason(null);
            
            UpdateQuizRequest request = new UpdateQuizRequest(
                "Updated Title", null, null, null, null, null, null, null, null, null
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("newHash"); // Changed!
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash123");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.updateQuiz("regularuser", quiz.getId(), request);
            
            // Then
            ArgumentCaptor<Quiz> quizCaptor = ArgumentCaptor.forClass(Quiz.class);
            verify(quizRepository).save(quizCaptor.capture());
            Quiz savedQuiz = quizCaptor.getValue();
            
            assertThat(savedQuiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
            assertThat(savedQuiz.getReviewedAt()).isNull();
            assertThat(savedQuiz.getReviewedBy()).isNull();
            assertThat(savedQuiz.getRejectionReason()).isNull();
        }
        
        @Test
        @DisplayName("Update PUBLISHED quiz with no content change - stays PUBLISHED")
        void updatePublishedQuiz_noContentChange_staysPublished() {
            // Given
            quiz.setStatus(QuizStatus.PUBLISHED);
            quiz.setContentHash("sameHash");
            
            UpdateQuizRequest request = new UpdateQuizRequest(
                null, "Updated description only", null, null, null, null, null, null, null, null
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("sameHash"); // No change
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.updateQuiz("regularuser", quiz.getId(), request);
            
            // Then
            ArgumentCaptor<Quiz> quizCaptor = ArgumentCaptor.forClass(Quiz.class);
            verify(quizRepository).save(quizCaptor.capture());
            Quiz savedQuiz = quizCaptor.getValue();
            
            assertThat(savedQuiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        }
        
        @Test
        @DisplayName("Update quiz with new category - changes category")
        void updateQuiz_withNewCategory_changesCategory() {
            // Given
            UpdateQuizRequest request = new UpdateQuizRequest(
                null, null, null, null, null, null, null, null, customCategory.getId(), null
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(categoryRepository.findById(customCategory.getId())).thenReturn(Optional.of(customCategory));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("hash");
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.updateQuiz("regularuser", quiz.getId(), request);
            
            // Then
            verify(quizMapper).updateEntity(eq(quiz), eq(request), eq(customCategory), any());
        }
        
        @Test
        @DisplayName("Update quiz with non-existent category throws exception")
        void updateQuiz_withNonExistentCategory_throwsException() {
            // Given
            UUID invalidCategoryId = UUID.randomUUID();
            UpdateQuizRequest request = new UpdateQuizRequest(
                null, null, null, null, null, null, null, null, invalidCategoryId, null
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(categoryRepository.findById(invalidCategoryId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.updateQuiz("regularuser", quiz.getId(), request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Category " + invalidCategoryId + " not found");
        }
        
        @Test
        @DisplayName("Update quiz with non-existent quiz throws exception")
        void updateQuiz_withNonExistentQuiz_throwsException() {
            // Given
            UUID nonExistentQuizId = UUID.randomUUID();
            UpdateQuizRequest request = new UpdateQuizRequest(
                "Title", null, null, null, null, null, null, null, null, null
            );
            
            when(quizRepository.findByIdWithTags(nonExistentQuizId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.updateQuiz("regularuser", nonExistentQuizId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Quiz " + nonExistentQuizId + " not found");
        }
        
        @Test
        @DisplayName("Update quiz recalculates content and presentation hashes")
        void updateQuiz_recalculatesHashes() {
            // Given
            UpdateQuizRequest request = new UpdateQuizRequest(
                "New Title", null, null, null, null, null, null, null, null, null
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("newContentHash");
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("newPresentationHash");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.updateQuiz("regularuser", quiz.getId(), request);
            
            // Then
            verify(quizHashCalculator).calculateContentHash(any(QuizDto.class));
            verify(quizHashCalculator).calculatePresentationHash(any(QuizDto.class));
            
            ArgumentCaptor<Quiz> quizCaptor = ArgumentCaptor.forClass(Quiz.class);
            verify(quizRepository).save(quizCaptor.capture());
            Quiz savedQuiz = quizCaptor.getValue();
            
            assertThat(savedQuiz.getContentHash()).isEqualTo("newContentHash");
            assertThat(savedQuiz.getPresentationHash()).isEqualTo("newPresentationHash");
        }
    }
    
    // =============== DELETE QUIZ Tests ===============
    
    @Nested
    @DisplayName("deleteQuizById Tests")
    class DeleteQuizByIdTests {
        
        @Test
        @DisplayName("Owner deletes their own quiz - succeeds")
        void owner_deleteOwnQuiz_succeeds() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any());
            
            // When
            quizCommandService.deleteQuizById("regularuser", quiz.getId());
            
            // Then
            verify(accessPolicy).requireOwnerOrAny(
                eq(regularUser),
                eq(regularUser.getId()),
                eq(PermissionName.QUIZ_ADMIN)
            );
            verify(quizRepository).deleteById(quiz.getId());
        }
        
        @Test
        @DisplayName("Admin deletes any quiz - succeeds")
        void admin_deleteAnyQuiz_succeeds() {
            // Given
            User adminUser = createUser("admin", RoleName.ROLE_ADMIN);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any());
            
            // When
            quizCommandService.deleteQuizById("admin", quiz.getId());
            
            // Then
            verify(quizRepository).deleteById(quiz.getId());
        }
        
        @Test
        @DisplayName("Other user deletes quiz - access denied")
        void otherUser_deleteQuiz_throwsForbidden() {
            // Given
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.deleteQuizById("otheruser", quiz.getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Owner or elevated permission required");
            
            verify(quizRepository, never()).deleteById(any());
        }
        
        @Test
        @DisplayName("Delete non-existent quiz throws exception")
        void deleteNonExistentQuiz_throwsException() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            
            when(quizRepository.findById(nonExistentId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.deleteQuizById("regularuser", nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Quiz " + nonExistentId + " not found");
            
            verify(quizRepository, never()).deleteById(any());
        }
        
        @Test
        @DisplayName("Delete quiz with null creator - only admin can delete")
        void deleteOrphanQuiz_onlyAdminCanDelete() {
            // Given
            quiz.setCreator(null);
            User adminUser = createUser("admin", RoleName.ROLE_ADMIN);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any());
            
            // When
            quizCommandService.deleteQuizById("admin", quiz.getId());
            
            // Then
            verify(accessPolicy).requireOwnerOrAny(eq(adminUser), eq(null), eq(PermissionName.QUIZ_ADMIN));
            verify(quizRepository).deleteById(quiz.getId());
        }
    }
    
    // =============== BULK DELETE Tests ===============
    
    @Nested
    @DisplayName("deleteQuizzesByIds Tests")
    class BulkDeleteTests {
        
        @Test
        @DisplayName("Bulk delete with owner quizzes - deletes all")
        void bulkDelete_ownerQuizzes_deletesAll() {
            // Given
            Quiz quiz1 = createQuiz("Quiz 1", regularUser);
            Quiz quiz2 = createQuiz("Quiz 2", regularUser);
            List<UUID> quizIds = List.of(quiz1.getId(), quiz2.getId());
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_ADMIN)).thenReturn(false);
            when(quizRepository.findAllById(quizIds)).thenReturn(List.of(quiz1, quiz2));
            when(accessPolicy.isOwner(regularUser, regularUser.getId())).thenReturn(true);
            
            // When
            quizCommandService.deleteQuizzesByIds("regularuser", quizIds);
            
            // Then
            ArgumentCaptor<Iterable<Quiz>> captor = ArgumentCaptor.forClass(Iterable.class);
            verify(quizRepository).deleteAll(captor.capture());
            List<Quiz> deleted = new ArrayList<>();
            captor.getValue().forEach(deleted::add);
            assertThat(deleted).hasSize(2).contains(quiz1, quiz2);
        }
        
        @Test
        @DisplayName("Bulk delete with mix of owned and others' quizzes - deletes only owned")
        void bulkDelete_mixedOwnership_deletesOnlyOwned() {
            // Given
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            Quiz ownedQuiz = createQuiz("Owned", regularUser);
            Quiz othersQuiz = createQuiz("Others", otherUser);
            List<UUID> quizIds = List.of(ownedQuiz.getId(), othersQuiz.getId());
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(accessPolicy.hasAny(regularUser, PermissionName.QUIZ_ADMIN)).thenReturn(false);
            when(quizRepository.findAllById(quizIds)).thenReturn(List.of(ownedQuiz, othersQuiz));
            when(accessPolicy.isOwner(regularUser, regularUser.getId())).thenReturn(true);
            when(accessPolicy.isOwner(regularUser, otherUser.getId())).thenReturn(false);
            
            // When
            quizCommandService.deleteQuizzesByIds("regularuser", quizIds);
            
            // Then
            ArgumentCaptor<Iterable<Quiz>> captor = ArgumentCaptor.forClass(Iterable.class);
            verify(quizRepository).deleteAll(captor.capture());
            List<Quiz> deleted = new ArrayList<>();
            captor.getValue().forEach(deleted::add);
            assertThat(deleted).hasSize(1).contains(ownedQuiz).doesNotContain(othersQuiz);
        }
        
        @Test
        @DisplayName("Admin bulk delete - deletes all quizzes")
        void admin_bulkDelete_deletesAll() {
            // Given
            User adminUser = createUser("admin", RoleName.ROLE_ADMIN);
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            Quiz quiz1 = createQuiz("Quiz 1", otherUser);
            Quiz quiz2 = createQuiz("Quiz 2", regularUser);
            List<UUID> quizIds = List.of(quiz1.getId(), quiz2.getId());
            
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
            when(accessPolicy.hasAny(adminUser, PermissionName.QUIZ_ADMIN)).thenReturn(true);
            when(quizRepository.findAllById(quizIds)).thenReturn(List.of(quiz1, quiz2));
            
            // When
            quizCommandService.deleteQuizzesByIds("admin", quizIds);
            
            // Then
            ArgumentCaptor<Iterable<Quiz>> captor = ArgumentCaptor.forClass(Iterable.class);
            verify(quizRepository).deleteAll(captor.capture());
            List<Quiz> deleted = new ArrayList<>();
            captor.getValue().forEach(deleted::add);
            assertThat(deleted).hasSize(2);
        }
        
        @Test
        @DisplayName("Bulk delete with null list - does nothing")
        void bulkDelete_withNullList_doesNothing() {
            // When
            quizCommandService.deleteQuizzesByIds("regularuser", null);
            
            // Then
            verify(userRepository, never()).findByUsername(any());
            verify(quizRepository, never()).findAllById(any());
            verify(quizRepository, never()).deleteAll(any());
        }
        
        @Test
        @DisplayName("Bulk delete with empty list - does nothing")
        void bulkDelete_withEmptyList_doesNothing() {
            // When
            quizCommandService.deleteQuizzesByIds("regularuser", List.of());
            
            // Then
            verify(userRepository, never()).findByUsername(any());
            verify(quizRepository, never()).findAllById(any());
            verify(quizRepository, never()).deleteAll(any());
        }
        
        @Test
        @DisplayName("Bulk delete with non-existent user throws exception")
        void bulkDelete_withNonExistentUser_throwsException() {
            // Given
            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("nonexistent")).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.deleteQuizzesByIds("nonexistent", List.of(UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User nonexistent not found");
        }
        
        @Test
        @DisplayName("Bulk delete with no permission on any quiz - deletes nothing")
        void bulkDelete_noPermissionOnAny_deletesNothing() {
            // Given
            User otherUser = createUser("otheruser", RoleName.ROLE_USER);
            Quiz quiz1 = createQuiz("Quiz 1", regularUser);
            Quiz quiz2 = createQuiz("Quiz 2", regularUser);
            List<UUID> quizIds = List.of(quiz1.getId(), quiz2.getId());
            
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            when(accessPolicy.hasAny(otherUser, PermissionName.QUIZ_ADMIN)).thenReturn(false);
            when(quizRepository.findAllById(quizIds)).thenReturn(List.of(quiz1, quiz2));
            when(accessPolicy.isOwner(otherUser, regularUser.getId())).thenReturn(false);
            
            // When
            quizCommandService.deleteQuizzesByIds("otheruser", quizIds);
            
            // Then
            verify(quizRepository, never()).deleteAll(any());
        }
    }
    
    // =============== BULK UPDATE Tests ===============
    
    @Nested
    @DisplayName("bulkUpdateQuiz Tests")
    class BulkUpdateTests {
        
        @Test
        @DisplayName("Bulk update with all successful updates")
        void bulkUpdate_allSuccessful() {
            // Given
            Quiz quiz1 = createQuiz("Quiz 1", regularUser);
            Quiz quiz2 = createQuiz("Quiz 2", regularUser);
            UpdateQuizRequest update = new UpdateQuizRequest(
                "Updated", null, null, null, null, null, null, null, null, null
            );
            BulkQuizUpdateRequest request = new BulkQuizUpdateRequest(
                List.of(quiz1.getId(), quiz2.getId()),
                update
            );
            
            when(quizRepository.findByIdWithTags(quiz1.getId())).thenReturn(Optional.of(quiz1));
            when(quizRepository.findByIdWithTags(quiz2.getId())).thenReturn(Optional.of(quiz2));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("hash");
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz1, quiz2);
            
            // When
            BulkQuizUpdateOperationResultDto result = quizCommandService.bulkUpdateQuiz("regularuser", request);
            
            // Then
            assertThat(result.successfulIds()).hasSize(2);
            assertThat(result.failures()).isEmpty();
            assertThat(result.successfulIds()).containsExactlyInAnyOrder(quiz1.getId(), quiz2.getId());
        }
        
        @Test
        @DisplayName("Bulk update with partial failures")
        void bulkUpdate_partialFailures() {
            // Given
            Quiz quiz1 = createQuiz("Quiz 1", regularUser);
            UUID nonExistentId = UUID.randomUUID();
            UpdateQuizRequest update = new UpdateQuizRequest(
                "Updated", null, null, null, null, null, null, null, null, null
            );
            BulkQuizUpdateRequest request = new BulkQuizUpdateRequest(
                List.of(quiz1.getId(), nonExistentId),
                update
            );
            
            when(quizRepository.findByIdWithTags(quiz1.getId())).thenReturn(Optional.of(quiz1));
            when(quizRepository.findByIdWithTags(nonExistentId)).thenReturn(Optional.empty());
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("hash");
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz1);
            
            // When
            BulkQuizUpdateOperationResultDto result = quizCommandService.bulkUpdateQuiz("regularuser", request);
            
            // Then
            assertThat(result.successfulIds()).hasSize(1);
            assertThat(result.successfulIds()).contains(quiz1.getId());
            assertThat(result.failures()).hasSize(1);
            assertThat(result.failures()).containsKey(nonExistentId);
            assertThat(result.failures().get(nonExistentId)).contains("Quiz " + nonExistentId + " not found");
        }
        
        @Test
        @DisplayName("Bulk update with all failures")
        void bulkUpdate_allFailures() {
            // Given
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            UpdateQuizRequest update = new UpdateQuizRequest(
                "Updated", null, null, null, null, null, null, null, null, null
            );
            BulkQuizUpdateRequest request = new BulkQuizUpdateRequest(
                List.of(id1, id2),
                update
            );
            
            when(quizRepository.findByIdWithTags(id1)).thenReturn(Optional.empty());
            when(quizRepository.findByIdWithTags(id2)).thenReturn(Optional.empty());
            
            // When
            BulkQuizUpdateOperationResultDto result = quizCommandService.bulkUpdateQuiz("regularuser", request);
            
            // Then
            assertThat(result.successfulIds()).isEmpty();
            assertThat(result.failures()).hasSize(2);
            assertThat(result.failures()).containsKeys(id1, id2);
        }
        
        @Test
        @DisplayName("Bulk update catches and records exceptions")
        void bulkUpdate_catchesExceptions() {
            // Given
            Quiz quiz1 = createQuiz("Quiz 1", regularUser);
            UpdateQuizRequest update = new UpdateQuizRequest(
                "Updated", null, null, null, null, null, null, null, null, null
            );
            BulkQuizUpdateRequest request = new BulkQuizUpdateRequest(
                List.of(quiz1.getId()),
                update
            );
            
            when(quizRepository.findByIdWithTags(quiz1.getId())).thenReturn(Optional.of(quiz1));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doThrow(new ForbiddenException("Access denied"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When
            BulkQuizUpdateOperationResultDto result = quizCommandService.bulkUpdateQuiz("regularuser", request);
            
            // Then
            assertThat(result.successfulIds()).isEmpty();
            assertThat(result.failures()).hasSize(1);
            assertThat(result.failures().get(quiz1.getId())).contains("Access denied");
        }
    }
    
    // =============== Category Resolution Tests ===============
    
    @Nested
    @DisplayName("Category Resolution Tests")
    class CategoryResolutionTests {
        
        @Test
        @DisplayName("Null categoryId uses default category")
        void nullCategoryId_usesDefault() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                null, // Null category
                List.of()
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizMapper.toEntity(any(), any(), eq(defaultCategory), any())).thenReturn(quiz);
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.createQuiz("regularuser", request);
            
            // Then
            verify(categoryRepository).findById(DEFAULT_CATEGORY_ID);
            verify(quizMapper).toEntity(any(), any(), eq(defaultCategory), any());
        }
        
        @Test
        @DisplayName("Invalid categoryId falls back to default with warning")
        void invalidCategoryId_fallsBackToDefault() {
            // Given
            UUID invalidId = UUID.randomUUID();
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                invalidId,
                List.of()
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(categoryRepository.findById(invalidId)).thenReturn(Optional.empty());
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizMapper.toEntity(any(), any(), eq(defaultCategory), any())).thenReturn(quiz);
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.createQuiz("regularuser", request);
            
            // Then
            verify(categoryRepository).findById(invalidId);
            verify(categoryRepository).findById(DEFAULT_CATEGORY_ID);
            verify(quizMapper).toEntity(any(), any(), eq(defaultCategory), any());
        }
        
        @Test
        @DisplayName("Valid custom categoryId uses requested category")
        void validCustomCategoryId_usesRequested() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                customCategory.getId(),
                List.of()
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(categoryRepository.findById(customCategory.getId())).thenReturn(Optional.of(customCategory));
            when(accessPolicy.hasAny(any(), any(), any())).thenReturn(false);
            when(quizMapper.toEntity(any(), any(), eq(customCategory), any())).thenReturn(quiz);
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.createQuiz("regularuser", request);
            
            // Then
            verify(categoryRepository).findById(customCategory.getId());
            verify(quizMapper).toEntity(any(), any(), eq(customCategory), any());
        }
        
        @Test
        @DisplayName("Missing default category throws IllegalStateException")
        void missingDefaultCategory_throwsIllegalStateException() {
            // Given
            CreateQuizRequest request = new CreateQuizRequest(
                "Quiz", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false,
                10, 5,
                null,
                List.of()
            );
            
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(categoryRepository.findById(DEFAULT_CATEGORY_ID)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.createQuiz("regularuser", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configured default category");
        }
    }
    
    // =============== Edge Cases Tests ===============
    
    @Nested
    @DisplayName("Edge Cases and Defensive Programming")
    class EdgeCases {
        
        @Test
        @DisplayName("Update quiz with null tagIds preserves existing tags")
        void updateQuiz_withNullTagIds_preservesExisting() {
            // Given
            UpdateQuizRequest request = new UpdateQuizRequest(
                "New Title", null, null, null, null, null, null, null, null,
                null // Null tagIds
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("hash");
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.updateQuiz("regularuser", quiz.getId(), request);
            
            // Then
            verify(quizMapper).updateEntity(eq(quiz), eq(request), any(), eq(null));
        }
        
        @Test
        @DisplayName("Update quiz with non-existent tag throws exception")
        void updateQuiz_withNonExistentTag_throwsException() {
            // Given
            UUID invalidTagId = UUID.randomUUID();
            UpdateQuizRequest request = new UpdateQuizRequest(
                null, null, null, null, null, null, null, null, null,
                List.of(invalidTagId)
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            when(tagRepository.findById(invalidTagId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> quizCommandService.updateQuiz("regularuser", quiz.getId(), request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag " + invalidTagId + " not found");
        }
        
        @Test
        @DisplayName("Update with null before content hash does not trigger PENDING_REVIEW")
        void updateWithNullBeforeHash_doesNotTriggerPendingReview() {
            // Given
            quiz.setStatus(QuizStatus.PUBLISHED);
            quiz.setContentHash(null); // No previous hash
            
            UpdateQuizRequest request = new UpdateQuizRequest(
                "Updated", null, null, null, null, null, null, null, null, null
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("newHash");
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.updateQuiz("regularuser", quiz.getId(), request);
            
            // Then
            ArgumentCaptor<Quiz> quizCaptor = ArgumentCaptor.forClass(Quiz.class);
            verify(quizRepository).save(quizCaptor.capture());
            
            // Should remain PUBLISHED (no before hash to compare)
            assertThat(quizCaptor.getValue().getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        }
        
        @Test
        @DisplayName("Update DRAFT quiz does not trigger PENDING_REVIEW even with content change")
        void updateDraftQuiz_doesNotTriggerPendingReview() {
            // Given
            quiz.setStatus(QuizStatus.DRAFT);
            quiz.setContentHash("oldHash");
            
            UpdateQuizRequest request = new UpdateQuizRequest(
                "Updated", null, null, null, null, null, null, null, null, null
            );
            
            when(quizRepository.findByIdWithTags(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(regularUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizHashCalculator.calculateContentHash(any())).thenReturn("newHash"); // Changed!
            when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("pHash");
            when(quizMapper.toDto(any())).thenReturn(mock(QuizDto.class));
            when(quizRepository.save(any())).thenReturn(quiz);
            
            // When
            quizCommandService.updateQuiz("regularuser", quiz.getId(), request);
            
            // Then
            ArgumentCaptor<Quiz> quizCaptor = ArgumentCaptor.forClass(Quiz.class);
            verify(quizRepository).save(quizCaptor.capture());
            
            // Should remain DRAFT (only PUBLISHED triggers transition)
            assertThat(quizCaptor.getValue().getStatus()).isEqualTo(QuizStatus.DRAFT);
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
    
    private Quiz createQuiz(String title, User creator) {
        Quiz q = new Quiz();
        q.setId(UUID.randomUUID());
        q.setTitle(title);
        q.setDescription("Description");
        q.setCreator(creator);
        q.setCategory(defaultCategory);
        q.setVisibility(Visibility.PRIVATE);
        q.setStatus(QuizStatus.DRAFT);
        q.setEstimatedTime(10);
        q.setQuestions(new HashSet<>());
        q.setTags(new HashSet<>());
        return q;
    }
}

