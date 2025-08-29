package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Instant;
import java.util.*;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AttemptControllerCurrentQuestionIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private User testUser;
    private Quiz testQuiz;
    private Question question1;
    private Question question2;
    private Attempt testAttempt;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // Clean up any existing test data first
        userRepository.findByUsername("testuser").ifPresent(userRepository::delete);
        userRepository.findByEmail("test@example.com").ifPresent(userRepository::delete);
        
        // Use unique names with timestamp to avoid conflicts
        String timestamp = String.valueOf(System.currentTimeMillis());
        String uniqueEmail = "test_" + timestamp + "@example.com";
        String uniqueQuizTitle = "Test Quiz " + timestamp;
        String uniqueCategoryName = "Test Category " + timestamp;

        // Create test user with fixed username
        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setEmail(uniqueEmail);
        testUser.setHashedPassword("password123");
        testUser = userRepository.save(testUser);

        // Create test category (required for quiz)
        Category testCategory = new Category();
        testCategory.setName(uniqueCategoryName);
        testCategory.setDescription("A test category");
        testCategory = context.getBean(CategoryRepository.class).save(testCategory);

        // Create test quiz
        testQuiz = new Quiz();
        testQuiz.setTitle(uniqueQuizTitle);
        testQuiz.setDescription("A test quiz");
        testQuiz.setCreator(testUser);
        testQuiz.setCategory(testCategory);
        testQuiz.setVisibility(Visibility.PUBLIC);
        testQuiz.setStatus(QuizStatus.PUBLISHED);
        testQuiz.setDifficulty(Difficulty.EASY);
        testQuiz.setEstimatedTime(10);
        testQuiz.setIsRepetitionEnabled(false);
        testQuiz.setIsTimerEnabled(false);
        testQuiz.setIsDeleted(false);
        testQuiz = quizRepository.save(testQuiz);

        // Create questions
        question1 = new Question();
        question1.setType(QuestionType.MCQ_SINGLE);
        question1.setQuestionText("What is the capital of France?");
        question1.setContent("""
                {
                  "options": [
                    {"id": "1", "text": "London", "correct": false},
                    {"id": "2", "text": "Berlin", "correct": false},
                    {"id": "3", "text": "Paris", "correct": true},
                    {"id": "4", "text": "Madrid", "correct": false}
                  ]
                }
                """);
        question1.setDifficulty(Difficulty.EASY);
        question1.setHint("Think about the Eiffel Tower");
        question1.setExplanation("Paris is the capital and largest city of France.");
        question1.setIsDeleted(false);
        question1 = questionRepository.save(question1);

        question2 = new Question();
        question2.setType(QuestionType.MCQ_SINGLE);
        question2.setQuestionText("What is 2 + 2?");
        question2.setContent("""
                {
                  "options": [
                    {"id": "1", "text": "3", "correct": false},
                    {"id": "2", "text": "4", "correct": true},
                    {"id": "3", "text": "5", "correct": false},
                    {"id": "4", "text": "6", "correct": false}
                  ]
                }
                """);
        question2.setDifficulty(Difficulty.EASY);
        question2.setHint("Basic arithmetic");
        question2.setExplanation("2 + 2 = 4 is a fundamental mathematical fact.");
        question2.setIsDeleted(false);
        question2 = questionRepository.save(question2);

        // Add questions to quiz
        Set<Question> questions = new HashSet<>();
        questions.add(question1);
        questions.add(question2);
        testQuiz.setQuestions(questions);
        testQuiz = quizRepository.save(testQuiz);

        // Create test attempt
        testAttempt = new Attempt();
        testAttempt.setUser(testUser);
        testAttempt.setQuiz(testQuiz);
        testAttempt.setMode(AttemptMode.ONE_BY_ONE);
        testAttempt.setStatus(AttemptStatus.IN_PROGRESS);
        testAttempt.setStartedAt(Instant.now());
        testAttempt.setAnswers(new java.util.ArrayList<>());
        testAttempt = attemptRepository.save(testAttempt);
    }

    @Test
    @WithMockUser(username = "testuser")
    void getCurrentQuestion_FirstQuestion_ReturnsFirstQuestion() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/current-question", testAttempt.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionNumber").value(1))
                .andExpect(jsonPath("$.totalQuestions").value(2))
                .andExpect(jsonPath("$.attemptStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.question.id").exists())
                .andExpect(jsonPath("$.question.type").value("MCQ_SINGLE"))
                .andExpect(jsonPath("$.question.difficulty").value("EASY"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getCurrentQuestion_SecondQuestion_ReturnsSecondQuestion() throws Exception {
        // Arrange - Add one answer to simulate first question answered
        // We need to get the first question from the sorted list to answer it
        List<Question> sortedQuestions = testQuiz.getQuestions().stream()
                .sorted(Comparator.comparing(Question::getId))
                .collect(java.util.stream.Collectors.toList());
        Question first = sortedQuestions.get(0);
        
        Answer answer = new Answer();
        answer.setQuestion(first);
        answer.setAnsweredAt(Instant.now());
        answer.setAttempt(testAttempt);
        answer.setResponse("{\"selectedOption\": \"4\"}");  // User selected "4" as answer
        answer.setIsCorrect(true);
        answer.setScore(1.0);
        
        // Save the answer separately to ensure it's persisted
        AnswerRepository answerRepo = context.getBean(AnswerRepository.class);
        answerRepo.save(answer);

        // Act & Assert
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/current-question", testAttempt.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionNumber").value(2))
                .andExpect(jsonPath("$.totalQuestions").value(2))
                .andExpect(jsonPath("$.attemptStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.question.id").exists())
                .andExpect(jsonPath("$.question.type").value("MCQ_SINGLE"))
                .andExpect(jsonPath("$.question.difficulty").value("EASY"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getCurrentQuestion_AttemptNotFound_Returns404() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/current-question", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "wronguser")
    void getCurrentQuestion_WrongUser_Returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/current-question", testAttempt.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "testuser")
    void getCurrentQuestion_AttemptCompleted_Returns409() throws Exception {
        // Arrange - Complete the attempt by updating it directly
        testAttempt.setStatus(AttemptStatus.COMPLETED);
        testAttempt.setCompletedAt(Instant.now());
        attemptRepository.saveAndFlush(testAttempt);

        // Act & Assert
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/current-question", testAttempt.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Processing Failed"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getCurrentQuestion_AllQuestionsAnswered_Returns409() throws Exception {
        // Arrange - Answer all questions
        List<Question> sortedQuestions = testQuiz.getQuestions().stream()
                .sorted(Comparator.comparing(Question::getId))
                .collect(java.util.stream.Collectors.toList());
        
        Answer answer1 = new Answer();
        answer1.setQuestion(sortedQuestions.get(0));
        answer1.setAnsweredAt(Instant.now());
        answer1.setAttempt(testAttempt);
        answer1.setResponse("{\"selectedOption\": \"4\"}");  // User selected "4" as answer
        answer1.setIsCorrect(true);
        answer1.setScore(1.0);

        Answer answer2 = new Answer();
        answer2.setQuestion(sortedQuestions.get(1));
        answer2.setAnsweredAt(Instant.now());
        answer2.setAttempt(testAttempt);
        answer2.setResponse("{\"selectedOption\": \"6\"}");  // User selected "6" as answer
        answer2.setIsCorrect(true);
        answer2.setScore(1.0);

        testAttempt.getAnswers().add(answer1);
        testAttempt.getAnswers().add(answer2);
        attemptRepository.saveAndFlush(testAttempt);

        // Act & Assert
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/current-question", testAttempt.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Processing Failed"));
    }
}