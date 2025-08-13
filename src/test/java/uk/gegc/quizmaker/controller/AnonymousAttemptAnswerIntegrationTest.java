package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uk.gegc.quizmaker.dto.attempt.StartAttemptRequest;
import uk.gegc.quizmaker.dto.quiz.CreateShareLinkRequest;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.ShareLinkScope;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "quizmaker.share-links.token-pepper=test-pepper-for-integration-tests"
})
@DisplayName("Anonymous Answer Submission Integration Tests")
class AnonymousAttemptAnswerIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    UserRepository userRepository;
    @Autowired
    QuizRepository quizRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    QuestionRepository questionRepository;

    private UUID quizId;
    private UUID questionId;
    private String userToken;

    @BeforeEach
    void setup() {
        jdbcTemplate.execute("DELETE FROM answers");
        jdbcTemplate.execute("DELETE FROM attempts");
        jdbcTemplate.execute("DELETE FROM share_link_analytics");
        jdbcTemplate.execute("DELETE FROM share_link_usage");
        jdbcTemplate.execute("DELETE FROM share_links");
        jdbcTemplate.execute("DELETE FROM quiz_questions");
        jdbcTemplate.execute("DELETE FROM quiz_tags");
        jdbcTemplate.execute("DELETE FROM questions");
        jdbcTemplate.execute("DELETE FROM quizzes");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM categories");

        User owner = new User();
        owner.setUsername("owner");
        owner.setEmail("owner@example.com");
        owner.setHashedPassword("password");
        owner.setActive(true);
        owner.setDeleted(false);
        owner = userRepository.save(owner);
        userToken = owner.getId().toString();

        Category category = new Category();
        category.setName("AnonCat");
        category.setDescription("AnonCat");
        category = categoryRepository.save(category);

        Quiz quiz = new Quiz();
        quiz.setTitle("Anon Quiz");
        quiz.setDescription("Anon start quiz");
        quiz.setCreator(owner);
        quiz.setCategory(category);
        quiz.setStatus(uk.gegc.quizmaker.model.quiz.QuizStatus.PUBLISHED);
        quiz.setVisibility(uk.gegc.quizmaker.model.quiz.Visibility.PUBLIC);
        quiz.setDifficulty(uk.gegc.quizmaker.model.question.Difficulty.EASY);
        quiz.setEstimatedTime(5);
        quiz.setIsDeleted(false);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz = quizRepository.save(quiz);
        quizId = quiz.getId();

        Question q = new Question();
        q.setType(QuestionType.TRUE_FALSE);
        q.setQuestionText("The sky is blue?");
        q.setDifficulty(uk.gegc.quizmaker.model.question.Difficulty.EASY);
        q.setContent("{\"prompt\":\"The sky is blue?\",\"answer\":true}");
        q = questionRepository.save(q);
        q.getQuizId().add(quiz);
        questionRepository.save(q);
        questionId = q.getId();
    }

    @Test
    @DisplayName("Anonymous can submit an answer via shared attempt endpoint")
    void anonymousSubmitAnswer_success() throws Exception {
        // Create share link
        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, Instant.now().plusSeconds(600), false);
        MvcResult created = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(created.getResponse().getContentAsString()).get("token").asText();

        // Access to set share_token cookie
        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
                        .header("User-Agent", "JUnit")
                        .header("X-Forwarded-For", "198.51.100.5"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("share_token"));

        // Start attempt
        StartAttemptRequest startReq = new StartAttemptRequest(uk.gegc.quizmaker.model.attempt.AttemptMode.ALL_AT_ONCE);
        MvcResult started = mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID attemptId = UUID.fromString(objectMapper.readTree(started.getResponse().getContentAsString()).get("attemptId").asText());

        // Submit answer
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", questionId.toString());
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("answer", true);
        payload.set("response", resp);

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Anonymous answer fails without share token cookie")
    void anonymousSubmitAnswer_missingCookie_returns400() throws Exception {
        // Arrange shared link and attempt
        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, Instant.now().plusSeconds(600), false);
        MvcResult created = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(created.getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
                        .header("User-Agent", "JUnit"))
                .andExpect(status().isOk());
        StartAttemptRequest startReq = new StartAttemptRequest(uk.gegc.quizmaker.model.attempt.AttemptMode.ALL_AT_ONCE);
        UUID attemptId = UUID.fromString(objectMapper.readTree(
                mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(startReq)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString()).get("attemptId").asText());

        // Submit without cookie
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", questionId.toString());
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("answer", true);
        payload.set("response", resp);

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Anonymous answer fails when token quiz doesn't match attempt quiz")
    void anonymousSubmitAnswer_quizMismatch_returns404() throws Exception {
        // Create two quizzes and link question to first quiz already done in setup
        User owner = userRepository.findAll().get(0);
        Category another = new Category();
        another.setName("Another");
        another.setDescription("Another");
        another = categoryRepository.save(another);
        Quiz otherQuiz = new Quiz();
        otherQuiz.setTitle("Other");
        otherQuiz.setDescription("Other");
        otherQuiz.setCreator(owner);
        otherQuiz.setCategory(another);
        otherQuiz.setStatus(uk.gegc.quizmaker.model.quiz.QuizStatus.PUBLISHED);
        otherQuiz.setVisibility(uk.gegc.quizmaker.model.quiz.Visibility.PUBLIC);
        otherQuiz.setDifficulty(uk.gegc.quizmaker.model.question.Difficulty.EASY);
        otherQuiz.setEstimatedTime(5);
        otherQuiz.setIsDeleted(false);
        otherQuiz.setIsRepetitionEnabled(false);
        otherQuiz.setIsTimerEnabled(false);
        otherQuiz = quizRepository.save(otherQuiz);

        // Create share link for other quiz
        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, Instant.now().plusSeconds(600), false);
        String tokenOther = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", otherQuiz.getId())
                                .with(user(userToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString()).get("token").asText();

        // Start attempt for original quiz (using its token)
        String tokenOriginal = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                                .with(user(userToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", tokenOriginal))
                .andExpect(status().isOk());
        StartAttemptRequest startReq = new StartAttemptRequest(uk.gegc.quizmaker.model.attempt.AttemptMode.ALL_AT_ONCE);
        UUID attemptId = UUID.fromString(objectMapper.readTree(
                mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", tokenOriginal)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(startReq)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString()).get("attemptId").asText());

        // Try to submit using cookie from other quiz token (mismatch)
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", questionId.toString());
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("answer", true);
        payload.set("response", resp);

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .cookie(new jakarta.servlet.http.Cookie("share_token", tokenOther))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNotFound());
    }
}


