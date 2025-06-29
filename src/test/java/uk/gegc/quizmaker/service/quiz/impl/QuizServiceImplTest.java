package uk.gegc.quizmaker.service.quiz.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.QuizMapper;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("QuizService Publishing Validation Tests")
class QuizServiceImplTest {

    @Mock
    private QuizRepository quizRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private QuizMapper quizMapper;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private QuizServiceImpl quizService;

    @Test
    @DisplayName("setStatus: Publishing quiz without questions should throw IllegalArgumentException")
    void setStatus_publishingEmptyQuiz_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz emptyQuiz = createQuizWithoutQuestions();
        
        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(emptyQuiz));

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot publish quiz without questions");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with questions should succeed")
    void setStatus_publishingQuizWithQuestions_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quizWithQuestions = createQuizWithQuestions(3);
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.PUBLISHED);
        
        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quizWithQuestions));
        when(quizRepository.save(quizWithQuestions)).thenReturn(quizWithQuestions);
        when(quizMapper.toDto(quizWithQuestions)).thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(quizWithQuestions.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizRepository).save(quizWithQuestions);
    }

    @Test
    @DisplayName("setStatus: Setting to DRAFT should work regardless of question count")
    void setStatus_settingToDraft_worksWithEmptyQuiz() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz emptyQuiz = createQuizWithoutQuestions();
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.DRAFT);
        
        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(emptyQuiz));
        when(quizRepository.save(emptyQuiz)).thenReturn(emptyQuiz);
        when(quizMapper.toDto(emptyQuiz)).thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.DRAFT);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(emptyQuiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        verify(quizRepository).save(emptyQuiz);
    }

    @Test
    @DisplayName("setStatus: Quiz not found should throw ResourceNotFoundException")
    void setStatus_quizNotFound_throwsResourceNotFoundException() {
        // Given
        UUID nonExistentQuizId = UUID.randomUUID();
        String username = "admin";
        
        when(quizRepository.findByIdWithQuestions(nonExistentQuizId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, nonExistentQuizId, QuizStatus.PUBLISHED))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Quiz " + nonExistentQuizId + " not found");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with single question should succeed")
    void setStatus_publishingQuizWithSingleQuestion_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quizWithOneQuestion = createQuizWithQuestions(1);
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.PUBLISHED);
        
        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quizWithOneQuestion));
        when(quizRepository.save(quizWithOneQuestion)).thenReturn(quizWithOneQuestion);
        when(quizMapper.toDto(quizWithOneQuestion)).thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(quizWithOneQuestion.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizRepository).save(quizWithOneQuestion);
    }

    @Test
    @DisplayName("setStatus: Transitioning from PUBLISHED back to DRAFT should work")
    void setStatus_publishedToDraft_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz publishedQuiz = createQuizWithQuestions(2);
        publishedQuiz.setStatus(QuizStatus.PUBLISHED);
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.DRAFT);
        
        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(publishedQuiz));
        when(quizRepository.save(publishedQuiz)).thenReturn(publishedQuiz);
        when(quizMapper.toDto(publishedQuiz)).thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.DRAFT);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(publishedQuiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        verify(quizRepository).save(publishedQuiz);
    }

    @Test
    @DisplayName("setStatus: Publishing already published quiz should work (idempotent)")
    void setStatus_publishingAlreadyPublishedQuiz_isIdempotent() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz alreadyPublishedQuiz = createQuizWithQuestions(3);
        alreadyPublishedQuiz.setStatus(QuizStatus.PUBLISHED);
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.PUBLISHED);
        
        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(alreadyPublishedQuiz));
        when(quizRepository.save(alreadyPublishedQuiz)).thenReturn(alreadyPublishedQuiz);
        when(quizMapper.toDto(alreadyPublishedQuiz)).thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(alreadyPublishedQuiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizRepository).save(alreadyPublishedQuiz);
    }

    // Helper methods for creating test data

    private Quiz createQuizWithoutQuestions() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Empty Quiz");
        quiz.setDescription("A quiz without questions");
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setCreatedAt(Instant.now());
        quiz.setQuestions(new HashSet<>()); // Empty questions set
        quiz.setCreator(createTestUser());
        quiz.setCategory(createTestCategory());
        return quiz;
    }

    private Quiz createQuizWithQuestions(int questionCount) {
        Quiz quiz = createQuizWithoutQuestions();
        quiz.setTitle("Quiz with " + questionCount + " questions");
        
        Set<Question> questions = new HashSet<>();
        for (int i = 0; i < questionCount; i++) {
            Question question = new Question();
            question.setId(UUID.randomUUID());
            question.setQuestionText("Question " + (i + 1));
            questions.add(question);
        }
        quiz.setQuestions(questions);
        return quiz;
    }

    private User createTestUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        return user;
    }

    private Category createTestCategory() {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Test Category");
        return category;
    }

    private QuizDto createQuizDto(UUID quizId, QuizStatus status) {
        return new QuizDto(
                quizId, // id
                UUID.randomUUID(), // creatorId
                UUID.randomUUID(), // categoryId
                "Test Quiz", // title
                "Test Description", // description
                null, // visibility
                null, // difficulty
                status, // status
                10, // estimatedTime
                false, // isRepetitionEnabled
                false, // timerEnabled
                5, // timerDuration
                List.of(), // tagIds
                Instant.now(), // createdAt
                null // updatedAt
        );
    }
} 