package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create"
})
@DisplayName("Quiz Controller Generate From Text Tests")
class QuizControllerGenerateFromTextTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private GenerateQuizFromTextRequest validRequest;

    @BeforeEach
    void setUp() {
        // Clear database before each test - delete in proper order to avoid foreign key constraints
        // Note: In a real test, you might want to use @Transactional and @Rollback instead
        // For now, we'll just create the user if it doesn't exist
        if (userRepository.findByUsername("testuser").isEmpty()) {
            User testUser = new User();
            testUser.setUsername("testuser");
            testUser.setEmail("testuser@example.com");
            testUser.setHashedPassword("hashedPassword");
            testUser.setActive(true);
            testUser.setDeleted(false);
            userRepository.save(testUser);
        }

        validRequest = new GenerateQuizFromTextRequest(
                "This is a sample text for quiz generation. It contains enough content to be processed and should generate meaningful questions.",
                "en",
                null, // chunkingStrategy - will use default
                null, // maxChunkSize - will use default
                null, // quizScope - will use default
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test Quiz from Text", // quizTitle
                "Test Description for text-based quiz", // quizDescription
                Map.of(QuestionType.MCQ_SINGLE, 3, QuestionType.TRUE_FALSE, 2), // questionsPerType
                Difficulty.MEDIUM, // difficulty
                2, // estimatedTimePerQuestion
                null, // categoryId
                null  // tagIds
        );
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 202 Accepted for valid request")
    @WithMockUser(username = "testuser", roles = "ADMIN")
    void generateFromText_ValidRequest_Returns202Accepted() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.message").value("Quiz generation started successfully"))
                .andExpect(jsonPath("$.estimatedTimeSeconds").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 400 for empty text")
    @WithMockUser(username = "testuser", roles = "ADMIN")
    void generateFromText_EmptyText_Returns400BadRequest() throws Exception {
        // Given
        GenerateQuizFromTextRequest invalidRequest = new GenerateQuizFromTextRequest(
                "", // empty text
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                "Test Quiz",
                "Test Description",
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM,
                2,
                null,
                null
        );

        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 400 for text exceeding 300k characters")
    @WithMockUser(username = "testuser", roles = "ADMIN")
    void generateFromText_TextTooLong_Returns400BadRequest() throws Exception {
        // Given
        String longText = "A".repeat(300001); // 300,001 characters
        GenerateQuizFromTextRequest invalidRequest = new GenerateQuizFromTextRequest(
                longText,
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                "Test Quiz",
                "Test Description",
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM,
                2,
                null,
                null
        );

        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 400 for missing questionsPerType")
    @WithMockUser(username = "testuser", roles = "ADMIN")
    void generateFromText_MissingQuestionsPerType_Returns400BadRequest() throws Exception {
        // Given
        GenerateQuizFromTextRequest invalidRequest = new GenerateQuizFromTextRequest(
                "Sample text",
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                "Test Quiz",
                "Test Description",
                null, // missing questionsPerType
                Difficulty.MEDIUM,
                2,
                null,
                null
        );

        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 400 for missing difficulty")
    @WithMockUser(username = "testuser", roles = "ADMIN")
    void generateFromText_MissingDifficulty_Returns400BadRequest() throws Exception {
        // Given
        GenerateQuizFromTextRequest invalidRequest = new GenerateQuizFromTextRequest(
                "Sample text",
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                "Test Quiz",
                "Test Description",
                Map.of(QuestionType.MCQ_SINGLE, 3),
                null, // missing difficulty
                2,
                null,
                null
        );

        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 403 for non-admin user")
    @WithMockUser(username = "regularuser", roles = "USER")
    void generateFromText_NonAdminUser_Returns403Forbidden() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 403 for unauthenticated user")
    void generateFromText_UnauthenticatedUser_Returns403Forbidden() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest))
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }
}
