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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.Visibility;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "admin", roles = {"ADMIN"})
class QuizControllerSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Category catGeneral;
    private Category catScience;
    private Tag tagJava;
    private Tag tagMath;

    @BeforeEach
    void setup() throws Exception {
        jdbcTemplate.execute("DELETE FROM quiz_tags");
        jdbcTemplate.execute("DELETE FROM quizzes");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM categories");
        jdbcTemplate.execute("DELETE FROM tags");

        User user = new User();
        user.setUsername("admin");
        user.setEmail("admin@example.com");
        user.setHashedPassword("pw");
        user.setActive(true);
        user.setDeleted(false);
        userRepository.save(user);

        catGeneral = new Category();
        catGeneral.setName("General");
        categoryRepository.save(catGeneral);

        catScience = new Category();
        catScience.setName("Science");
        categoryRepository.save(catScience);

        tagJava = new Tag();
        tagJava.setName("Java");
        tagRepository.save(tagJava);

        tagMath = new Tag();
        tagMath.setName("Math");
        tagRepository.save(tagMath);

        // Create data
        create(new CreateQuizRequest("Java Basics", "Intro", Visibility.PRIVATE, Difficulty.EASY, false, false, 10, 5, catGeneral.getId(), List.of(tagJava.getId())));
        create(new CreateQuizRequest("Advanced Math", "Algebra", Visibility.PRIVATE, Difficulty.HARD, false, false, 10, 5, catScience.getId(), List.of(tagMath.getId())));
        create(new CreateQuizRequest("Mixed Bag", "Java and math", Visibility.PRIVATE, Difficulty.MEDIUM, false, false, 10, 5, catGeneral.getId(), List.of(tagJava.getId(), tagMath.getId())));
    }

    private void create(CreateQuizRequest req) throws Exception {
        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes filters by category names")
    void filterByCategoryNames() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("category", "Science"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].title", is("Advanced Math")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes filters by tag names")
    void filterByTagNames() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("tag", "Java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", everyItem(anyOf(containsString("Java"), is("Mixed Bag")))));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes filters by author username")
    void filterByAuthorName() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("authorName", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes full-text search across title/description")
    void fullTextSearch() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("search", "java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", everyItem(anyOf(containsString("Java"), is("Mixed Bag")))));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes difficulty exact match")
    void difficultyMatch() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("difficulty", "HARD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", everyItem(is("Advanced Math"))));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes combined filters narrow to single result")
    void combinedFilters_singleResult() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("category", "General")
                        .param("tag", "Math")
                        .param("search", "java")
                        .param("authorName", "admin")
                        .param("difficulty", "MEDIUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].title", is("Mixed Bag")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes multiple values for category/tag")
    void multipleValues_categoryAndTag() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("category", "General", "Science")
                        .param("tag", "Java", "Math"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes invalid difficulty -> 400 Bad Request")
    void invalidDifficulty_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("difficulty", "IMPOSSIBLE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", anyOf(is("Bad Request"), is("Validation Failed"))));
    }
}


