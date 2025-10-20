package uk.gegc.quizmaker.features.quiz.application.command.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for QuizRelationServiceImpl.
 * 
 * <p>Tests cover all relationship management methods:
 * - Adding/removing questions
 * - Adding/removing tags
 * - Changing category
 * - Access control for all operations
 * - Edge cases and error conditions
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizRelationServiceImpl Tests")
class QuizRelationServiceImplTest {

    @Mock
    private QuizRepository quizRepository;
    
    @Mock
    private QuestionRepository questionRepository;
    
    @Mock
    private TagRepository tagRepository;
    
    @Mock
    private CategoryRepository categoryRepository;
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private AccessPolicy accessPolicy;
    
    @InjectMocks
    private QuizRelationServiceImpl relationService;
    
    private User ownerUser;
    private User otherUser;
    private User moderatorUser;
    private Quiz quiz;
    private Question question;
    private Tag tag;
    private Category category;
    
    @BeforeEach
    void setUp() {
        ownerUser = createUser("owner", UUID.randomUUID());
        otherUser = createUser("otheruser", UUID.randomUUID());
        moderatorUser = createUser("moderator", UUID.randomUUID());
        
        quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setCreator(ownerUser);
        quiz.setQuestions(new HashSet<>());
        quiz.setTags(new HashSet<>());
        
        question = new Question();
        question.setId(UUID.randomUUID());
        question.setQuestionText("Test question?");
        
        tag = new Tag();
        tag.setId(UUID.randomUUID());
        tag.setName("Test Tag");
        
        category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Test Category");
    }
    
    // =============== addQuestionToQuiz Tests ===============
    
    @Nested
    @DisplayName("addQuestionToQuiz() Tests")
    class AddQuestionToQuizTests {
        
        @Test
        @DisplayName("Owner adds question to their quiz - succeeds")
        void owner_addQuestion_succeeds() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.addQuestionToQuiz("owner", quiz.getId(), question.getId());
            
            // Then
            assertThat(quiz.getQuestions()).contains(question);
            verify(quizRepository).save(quiz);
        }
        
        @Test
        @DisplayName("Non-owner adds question - access denied")
        void nonOwner_addQuestion_throwsForbidden() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> relationService.addQuestionToQuiz("otheruser", quiz.getId(), question.getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Owner or elevated permission required");
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Moderator adds question to any quiz - succeeds")
        void moderator_addQuestion_succeeds() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.addQuestionToQuiz("moderator", quiz.getId(), question.getId());
            
            // Then
            assertThat(quiz.getQuestions()).contains(question);
        }
        
        @Test
        @DisplayName("Quiz not found - throws exception")
        void quizNotFound_addQuestion_throwsException() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> relationService.addQuestionToQuiz("owner", quiz.getId(), question.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Quiz " + quiz.getId() + " not found");
            
            verify(questionRepository, never()).findById(any());
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Question not found - throws exception")
        void questionNotFound_throwsException() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> relationService.addQuestionToQuiz("owner", quiz.getId(), question.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Question " + question.getId() + " not found");
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("User not found - throws exception")
        void userNotFound_addQuestion_throwsException() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("nonexistent")).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> relationService.addQuestionToQuiz("nonexistent", quiz.getId(), question.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User nonexistent not found");
        }
        
        @Test
        @DisplayName("User found by email - succeeds")
        void userFoundByEmail_addQuestion_succeeds() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
            when(userRepository.findByUsername("owner@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.addQuestionToQuiz("owner@example.com", quiz.getId(), question.getId());
            
            // Then
            assertThat(quiz.getQuestions()).contains(question);
            verify(userRepository).findByEmail("owner@example.com");
        }
        
        @Test
        @DisplayName("Orphan quiz (no creator) - moderator can add question")
        void orphanQuiz_moderatorAddQuestion_succeeds() {
            // Given
            quiz.setCreator(null);
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(eq(moderatorUser), eq(null), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.addQuestionToQuiz("moderator", quiz.getId(), question.getId());
            
            // Then
            verify(accessPolicy).requireOwnerOrAny(eq(moderatorUser), eq(null), any(), any());
        }
    }
    
    // =============== removeQuestionFromQuiz Tests ===============
    
    @Nested
    @DisplayName("removeQuestionFromQuiz() Tests")
    class RemoveQuestionFromQuizTests {
        
        @Test
        @DisplayName("Owner removes question from their quiz - succeeds")
        void owner_removeQuestion_succeeds() {
            // Given
            quiz.getQuestions().add(question);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.removeQuestionFromQuiz("owner", quiz.getId(), question.getId());
            
            // Then
            assertThat(quiz.getQuestions()).doesNotContain(question);
            verify(quizRepository).save(quiz);
        }
        
        @Test
        @DisplayName("Non-owner removes question - access denied")
        void nonOwner_removeQuestion_throwsForbidden() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> relationService.removeQuestionFromQuiz("otheruser", quiz.getId(), question.getId()))
                .isInstanceOf(ForbiddenException.class);
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Quiz not found - throws exception")
        void quizNotFound_removeQuestion_throwsException() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> relationService.removeQuestionFromQuiz("owner", quiz.getId(), question.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Quiz " + quiz.getId() + " not found");
        }
        
        @Test
        @DisplayName("Removing non-existent question - no error, just doesn't change quiz")
        void removeNonExistentQuestion_noError() {
            // Given - question not in quiz
            UUID nonExistentQuestionId = UUID.randomUUID();
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.removeQuestionFromQuiz("owner", quiz.getId(), nonExistentQuestionId);
            
            // Then - No exception, quiz saved anyway
            verify(quizRepository).save(quiz);
        }
    }
    
    // =============== addTagToQuiz Tests ===============
    
    @Nested
    @DisplayName("addTagToQuiz() Tests")
    class AddTagToQuizTests {
        
        @Test
        @DisplayName("Owner adds tag to their quiz - succeeds")
        void owner_addTag_succeeds() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(tagRepository.findById(tag.getId())).thenReturn(Optional.of(tag));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.addTagToQuiz("owner", quiz.getId(), tag.getId());
            
            // Then
            assertThat(quiz.getTags()).contains(tag);
            verify(quizRepository).save(quiz);
        }
        
        @Test
        @DisplayName("Non-owner adds tag - access denied")
        void nonOwner_addTag_throwsForbidden() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(tagRepository.findById(tag.getId())).thenReturn(Optional.of(tag));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> relationService.addTagToQuiz("otheruser", quiz.getId(), tag.getId()))
                .isInstanceOf(ForbiddenException.class);
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Tag not found - throws exception")
        void tagNotFound_throwsException() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(tagRepository.findById(tag.getId())).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> relationService.addTagToQuiz("owner", quiz.getId(), tag.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag " + tag.getId() + " not found");
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Adding same tag twice - no error")
        void addSameTagTwice_noError() {
            // Given
            quiz.getTags().add(tag); // Already present
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(tagRepository.findById(tag.getId())).thenReturn(Optional.of(tag));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.addTagToQuiz("owner", quiz.getId(), tag.getId());
            
            // Then - Tag still in set (Set behavior)
            assertThat(quiz.getTags()).hasSize(1);
            verify(quizRepository).save(quiz);
        }
    }
    
    // =============== removeTagFromQuiz Tests ===============
    
    @Nested
    @DisplayName("removeTagFromQuiz() Tests")
    class RemoveTagFromQuizTests {
        
        @Test
        @DisplayName("Owner removes tag from their quiz - succeeds")
        void owner_removeTag_succeeds() {
            // Given
            quiz.getTags().add(tag);
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.removeTagFromQuiz("owner", quiz.getId(), tag.getId());
            
            // Then
            assertThat(quiz.getTags()).doesNotContain(tag);
            verify(quizRepository).save(quiz);
        }
        
        @Test
        @DisplayName("Non-owner removes tag - access denied")
        void nonOwner_removeTag_throwsForbidden() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> relationService.removeTagFromQuiz("otheruser", quiz.getId(), tag.getId()))
                .isInstanceOf(ForbiddenException.class);
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Removing non-existent tag - no error")
        void removeNonExistentTag_noError() {
            // Given - tag not in quiz
            UUID nonExistentTagId = UUID.randomUUID();
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.removeTagFromQuiz("owner", quiz.getId(), nonExistentTagId);
            
            // Then - No exception, quiz saved
            verify(quizRepository).save(quiz);
        }
    }
    
    // =============== changeCategory Tests ===============
    
    @Nested
    @DisplayName("changeCategory() Tests")
    class ChangeCategoryTests {
        
        @Test
        @DisplayName("Owner changes category - succeeds")
        void owner_changeCategory_succeeds() {
            // Given
            Category newCategory = new Category();
            newCategory.setId(UUID.randomUUID());
            newCategory.setName("New Category");
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(categoryRepository.findById(newCategory.getId())).thenReturn(Optional.of(newCategory));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.changeCategory("owner", quiz.getId(), newCategory.getId());
            
            // Then
            assertThat(quiz.getCategory()).isEqualTo(newCategory);
            verify(quizRepository).save(quiz);
        }
        
        @Test
        @DisplayName("Non-owner changes category - access denied")
        void nonOwner_changeCategory_throwsForbidden() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Owner or elevated permission required"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            // When & Then
            assertThatThrownBy(() -> relationService.changeCategory("otheruser", quiz.getId(), category.getId()))
                .isInstanceOf(ForbiddenException.class);
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Category not found - throws exception")
        void categoryNotFound_throwsException() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(categoryRepository.findById(category.getId())).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> relationService.changeCategory("owner", quiz.getId(), category.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Category " + category.getId() + " not found");
            
            verify(quizRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Moderator changes category on any quiz - succeeds")
        void moderator_changeCategory_succeeds() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
            when(userRepository.findByUsername("moderator")).thenReturn(Optional.of(moderatorUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.changeCategory("moderator", quiz.getId(), category.getId());
            
            // Then
            assertThat(quiz.getCategory()).isEqualTo(category);
        }
    }
    
    // =============== Access Control Integration Tests ===============
    
    @Nested
    @DisplayName("Access Control Integration Tests")
    class AccessControlTests {
        
        @Test
        @DisplayName("AccessPolicy is invoked for all operations")
        void accessPolicy_invokedForAllOperations() {
            // Given - Setup for addQuestion
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.addQuestionToQuiz("owner", quiz.getId(), question.getId());
            
            // Then
            verify(accessPolicy).requireOwnerOrAny(
                eq(ownerUser),
                eq(ownerUser.getId()),
                eq(PermissionName.QUIZ_MODERATE),
                eq(PermissionName.QUIZ_ADMIN)
            );
        }
        
        @Test
        @DisplayName("Access check happens before modifications")
        void accessCheck_beforeModifications() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
            when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
            doThrow(new ForbiddenException("Access denied"))
                .when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            
            int initialSize = quiz.getQuestions().size();
            
            // When & Then
            assertThatThrownBy(() -> relationService.addQuestionToQuiz("otheruser", quiz.getId(), question.getId()))
                .isInstanceOf(ForbiddenException.class);
            
            // Quiz not modified
            assertThat(quiz.getQuestions()).hasSize(initialSize);
        }
    }
    
    // =============== Edge Cases Tests ===============
    
    @Nested
    @DisplayName("Edge Cases and Complex Scenarios")
    class EdgeCasesTests {
        
        @Test
        @DisplayName("Multiple questions can be added sequentially")
        void multipleQuestions_addedSequentially() {
            // Given
            Question q1 = createQuestion(UUID.randomUUID());
            Question q2 = createQuestion(UUID.randomUUID());
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(q1.getId())).thenReturn(Optional.of(q1));
            when(questionRepository.findById(q2.getId())).thenReturn(Optional.of(q2));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.addQuestionToQuiz("owner", quiz.getId(), q1.getId());
            relationService.addQuestionToQuiz("owner", quiz.getId(), q2.getId());
            
            // Then
            assertThat(quiz.getQuestions()).contains(q1, q2);
            verify(quizRepository, times(2)).save(quiz);
        }
        
        @Test
        @DisplayName("Multiple tags can be added sequentially")
        void multipleTags_addedSequentially() {
            // Given
            Tag tag1 = createTag(UUID.randomUUID(), "Tag1");
            Tag tag2 = createTag(UUID.randomUUID(), "Tag2");
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(tagRepository.findById(tag1.getId())).thenReturn(Optional.of(tag1));
            when(tagRepository.findById(tag2.getId())).thenReturn(Optional.of(tag2));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.addTagToQuiz("owner", quiz.getId(), tag1.getId());
            relationService.addTagToQuiz("owner", quiz.getId(), tag2.getId());
            
            // Then
            assertThat(quiz.getTags()).contains(tag1, tag2);
        }
        
        @Test
        @DisplayName("Category can be changed multiple times")
        void category_changedMultipleTimes() {
            // Given
            Category cat1 = createCategory(UUID.randomUUID(), "Category 1");
            Category cat2 = createCategory(UUID.randomUUID(), "Category 2");
            
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(categoryRepository.findById(cat1.getId())).thenReturn(Optional.of(cat1));
            when(categoryRepository.findById(cat2.getId())).thenReturn(Optional.of(cat2));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When
            relationService.changeCategory("owner", quiz.getId(), cat1.getId());
            assertThat(quiz.getCategory()).isEqualTo(cat1);
            
            relationService.changeCategory("owner", quiz.getId(), cat2.getId());
            assertThat(quiz.getCategory()).isEqualTo(cat2);
        }
        
        @Test
        @DisplayName("Add and remove question in sequence")
        void addThenRemoveQuestion_works() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(questionRepository.findById(question.getId())).thenReturn(Optional.of(question));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When - Add
            relationService.addQuestionToQuiz("owner", quiz.getId(), question.getId());
            assertThat(quiz.getQuestions()).contains(question);
            
            // When - Remove
            relationService.removeQuestionFromQuiz("owner", quiz.getId(), question.getId());
            assertThat(quiz.getQuestions()).doesNotContain(question);
        }
        
        @Test
        @DisplayName("Add and remove tag in sequence")
        void addThenRemoveTag_works() {
            // Given
            when(quizRepository.findById(quiz.getId())).thenReturn(Optional.of(quiz));
            when(tagRepository.findById(tag.getId())).thenReturn(Optional.of(tag));
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(ownerUser));
            doNothing().when(accessPolicy).requireOwnerOrAny(any(), any(), any(), any());
            when(quizRepository.save(quiz)).thenReturn(quiz);
            
            // When - Add
            relationService.addTagToQuiz("owner", quiz.getId(), tag.getId());
            assertThat(quiz.getTags()).contains(tag);
            
            // When - Remove
            relationService.removeTagFromQuiz("owner", quiz.getId(), tag.getId());
            assertThat(quiz.getTags()).doesNotContain(tag);
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
    
    private Question createQuestion(UUID questionId) {
        Question q = new Question();
        q.setId(questionId);
        q.setQuestionText("Question " + questionId);
        return q;
    }
    
    private Tag createTag(UUID tagId, String name) {
        Tag t = new Tag();
        t.setId(tagId);
        t.setName(name);
        return t;
    }
    
    private Category createCategory(UUID categoryId, String name) {
        Category c = new Category();
        c.setId(categoryId);
        c.setName(name);
        return c;
    }
}

