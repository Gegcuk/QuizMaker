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
import uk.gegc.quizmaker.dto.quiz.CreateShareLinkRequest;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.ShareLinkScope;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = AFTER_CLASS)
@TestPropertySource(properties = {
		"spring.jpa.hibernate.ddl-auto=create",
		"quizmaker.share-links.token-pepper=test-pepper-for-integration-tests"
})
@DisplayName("Rate Limiting Integration Tests for Share Links")
class ShareLinkRateLimitIntegrationTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	UserRepository userRepository;
	@Autowired
	QuizRepository quizRepository;
	@Autowired
	CategoryRepository categoryRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;

	private UUID userId;
	private UUID quizId;
	private String userToken;

	@BeforeEach
	void setUp() {
		// Clean DB
		jdbcTemplate.execute("DELETE FROM share_link_analytics");
		jdbcTemplate.execute("DELETE FROM share_link_usage");
		jdbcTemplate.execute("DELETE FROM share_links");
		jdbcTemplate.execute("DELETE FROM quiz_questions");
		jdbcTemplate.execute("DELETE FROM quiz_tags");
		jdbcTemplate.execute("DELETE FROM quizzes");
		jdbcTemplate.execute("DELETE FROM users");
		jdbcTemplate.execute("DELETE FROM categories");

		// Create user
		User user = new User();
		user.setUsername("rateuser");
		user.setEmail("rateuser@example.com");
		user.setHashedPassword("password");
		user.setActive(true);
		user.setDeleted(false);
		user = userRepository.save(user);
		userId = user.getId();
		userToken = user.getId().toString();

		// Create category + quiz owned by user
		Category category = new Category();
		category.setName("Rate Test Cat");
		category.setDescription("Rate tests");
		category = categoryRepository.save(category);

		Quiz quiz = new Quiz();
		quiz.setTitle("Rate Quiz");
		quiz.setDescription("Rate limit quiz");
		quiz.setCreator(user);
		quiz.setCategory(category);
		quiz.setStatus(uk.gegc.quizmaker.model.quiz.QuizStatus.PUBLISHED);
		quiz.setVisibility(uk.gegc.quizmaker.model.quiz.Visibility.PUBLIC);
		quiz.setDifficulty(uk.gegc.quizmaker.model.question.Difficulty.EASY);
		quiz.setEstimatedTime(5);
		quiz.setIsDeleted(false);
		quiz.setIsTimerEnabled(false);
		quiz.setIsRepetitionEnabled(true);
		quiz = quizRepository.save(quiz);
		quizId = quiz.getId();
	}

	@Test
	@DisplayName("POST /quizzes/{quizId}/share-link is limited to 10/min per user")
	void createShareLink_rateLimitExceeded_returns429() throws Exception {
		CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, Instant.now().plusSeconds(600), false);
		for (int i = 0; i < 10; i++) {
			mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
					.with(user(userToken))
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(req)))
					.andExpect(status().isCreated());
		}
		mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
				.with(user(userToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(req)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	@DisplayName("GET /quizzes/shared/{token} is limited to 60/min per IP+token")
	void accessSharedQuiz_rateLimitExceeded_returns429() throws Exception {
		// create link
		CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, Instant.now().plusSeconds(600), false);
		MvcResult created = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
				.with(user(userToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(req)))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
		String token = json.get("token").asText();

		for (int i = 0; i < 60; i++) {
			mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
					.header("User-Agent", "JUnit")
					.header("X-Forwarded-For", "203.0.113.5"))
					.andExpect(status().isOk());
		}
		mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
				.header("User-Agent", "JUnit")
				.header("X-Forwarded-For", "203.0.113.5"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));
	}

	@Test
    @DisplayName("POST /quizzes/shared/{token}/consume is limited to 60/min per IP+token")
	void consumeSharedToken_rateLimitExceeded_returns429() throws Exception {
		// create link (non one-time)
		CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, Instant.now().plusSeconds(600), false);
		MvcResult created = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
				.with(user(userToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(req)))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
		String token = json.get("token").asText();

        for (int i = 0; i < 60; i++) {
            mockMvc.perform(post("/api/v1/quizzes/shared/{token}/consume", token)
					.header("User-Agent", "JUnit")
					.header("X-Forwarded-For", "198.51.100.7"))
					.andExpect(status().isOk());
		}
        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/consume", token)
				.header("User-Agent", "JUnit")
				.header("X-Forwarded-For", "198.51.100.7"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));
	}
}


