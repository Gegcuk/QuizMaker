package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.enable-forwarded-headers=true",
        "spring.jpa.hibernate.ddl-auto=create"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class QuizControllerCachingAndRateLimitTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    private Category category;

    @BeforeEach
    void setup() {
        jdbcTemplate.execute("DELETE FROM quiz_tags");
        jdbcTemplate.execute("DELETE FROM quizzes");
        jdbcTemplate.execute("DELETE FROM user_roles");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM categories");

        User admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@example.com");
        admin.setHashedPassword("pw");
        admin.setActive(true);
        admin.setDeleted(false);
        userRepository.save(admin);

        category = new Category();
        category.setName("General");
        categoryRepository.save(category);
    }

    @Test
    @DisplayName("ETag: list quizzes returns 304 when If-None-Match matches")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void etag_ListQuizzes() throws Exception {
        // Create a quiz so the list isn't empty
        CreateQuizRequest req = new CreateQuizRequest("Quiz 1", null, Visibility.PRIVATE, Difficulty.EASY, false, false, 5, 2, category.getId(), java.util.List.of());
        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        String etag = mockMvc.perform(get("/api/v1/quizzes").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

        assertThat(etag).isNotBlank();

        mockMvc.perform(get("/api/v1/quizzes")
                        .param("page", "0").param("size", "10")
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));
    }

    @Test
    @DisplayName("ETag: public quizzes returns 304 when If-None-Match matches")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void etag_PublicQuizzes() throws Exception {
        // Create a public quiz
        CreateQuizRequest req = new CreateQuizRequest("PublicQ", null, Visibility.PUBLIC, Difficulty.MEDIUM, false, false, 5, 2, category.getId(), java.util.List.of());
        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        String etag = mockMvc.perform(get("/api/v1/quizzes/public").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

        assertThat(etag).isNotBlank();

        mockMvc.perform(get("/api/v1/quizzes/public")
                        .param("page", "0").param("size", "10")
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));
    }

    @Test
    @DisplayName("Rate limit: list quizzes 120/min per IP → 429 on overflow")
    void rateLimit_ListQuizzes() throws Exception {
        // Hit the endpoint 121 times with the same client IP
        for (int i = 0; i < 120; i++) {
            mockMvc.perform(get("/api/v1/quizzes").param("page", "0").param("size", "1")
                            .header("X-Forwarded-For", "203.0.113.10"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/quizzes").param("page", "0").param("size", "1")
                        .header("X-Forwarded-For", "203.0.113.10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("Rate limit: public quizzes 120/min per IP → 429 on overflow")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void rateLimit_PublicQuizzes() throws Exception {
        // Ensure at least one public quiz exists
        CreateQuizRequest req = new CreateQuizRequest("PubRL", null, Visibility.PUBLIC, Difficulty.EASY, false, false, 5, 2, category.getId(), java.util.List.of());
        mockMvc.perform(post("/api/v1/quizzes")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        for (int i = 0; i < 120; i++) {
            mockMvc.perform(get("/api/v1/quizzes/public").param("page", "0").param("size", "1")
                            .header("X-Forwarded-For", "198.51.100.25"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/quizzes/public").param("page", "0").param("size", "1")
                        .header("X-Forwarded-For", "198.51.100.25"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}


