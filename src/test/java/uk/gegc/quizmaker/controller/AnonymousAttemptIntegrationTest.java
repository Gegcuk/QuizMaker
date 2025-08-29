package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import uk.gegc.quizmaker.features.attempt.api.dto.StartAttemptRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateShareLinkRequest;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLinkScope;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "quizmaker.share-links.token-pepper=test-pepper-for-integration-tests"
})
@DisplayName("Anonymous Attempt Integration Tests")
class AnonymousAttemptIntegrationTest {

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
    @Autowired
    AnswerRepository answerRepository;

    private UUID quizId;
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
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setDifficulty(Difficulty.EASY);
        quiz.setEstimatedTime(5);
        quiz.setIsDeleted(false);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz = quizRepository.save(quiz);
        quizId = quiz.getId();

        // Add a question and link to quiz via join table
        Question q = new Question();
        q.setType(QuestionType.TRUE_FALSE);
        q.setQuestionText("The sky is blue?");
        q.setDifficulty(Difficulty.EASY);
        q.setContent("{\"prompt\":\"The sky is blue?\",\"answer\":true}");
        q = questionRepository.save(q);
        // Use owning side to persist join
        q.getQuizId().add(quiz);
        questionRepository.save(q);
    }

    @Test
    @DisplayName("POST /quizzes/shared/{token}/attempts starts attempt for anonymous user")
    void startAnonymousAttempt_startsAndReturns201() throws Exception {
        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, Instant.now().plusSeconds(600), false);
        MvcResult created = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        String token = json.get("token").asText();

        StartAttemptRequest startReq = new StartAttemptRequest(AttemptMode.ALL_AT_ONCE);
        MvcResult started = mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                .andReturn();

        JsonNode startedJson = objectMapper.readTree(started.getResponse().getContentAsString());
        UUID attemptId = UUID.fromString(startedJson.get("attemptId").asText());
        assertThat(attemptId).isNotNull();

        // Ensure answers table still empty initially
        long answerCount = answerRepository.countByAttemptId(attemptId);
        assertThat(answerCount).isZero();
    }
}


