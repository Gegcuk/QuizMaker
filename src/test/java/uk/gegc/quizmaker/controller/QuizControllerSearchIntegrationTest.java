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
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
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
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private RoleRepository roleRepository;

    private Category catGeneral;
    private Category catScience;
    private Tag tagJava;
    private Tag tagMath;

    @BeforeEach
    void setup() throws Exception {
        // Clean up in dependency order to avoid foreign key constraint violations
        jdbcTemplate.execute("DELETE FROM answers");
        jdbcTemplate.execute("DELETE FROM attempts");
        jdbcTemplate.execute("DELETE FROM quiz_questions");
        jdbcTemplate.execute("DELETE FROM quiz_tags");
        jdbcTemplate.execute("DELETE FROM question_tags");
        jdbcTemplate.execute("DELETE FROM questions");
        jdbcTemplate.execute("DELETE FROM quizzes");
        jdbcTemplate.execute("DELETE FROM user_roles");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM categories");
        jdbcTemplate.execute("DELETE FROM tags");
        jdbcTemplate.execute("DELETE FROM role_permissions");
        jdbcTemplate.execute("DELETE FROM roles");
        jdbcTemplate.execute("DELETE FROM permissions");

        // Create permissions first
        Permission quizCreatePermission = new Permission();
        quizCreatePermission.setPermissionName(PermissionName.QUIZ_CREATE.name());
        quizCreatePermission = permissionRepository.save(quizCreatePermission);

        Permission quizReadPermission = new Permission();
        quizReadPermission.setPermissionName(PermissionName.QUIZ_READ.name());
        quizReadPermission = permissionRepository.save(quizReadPermission);

        Permission quizUpdatePermission = new Permission();
        quizUpdatePermission.setPermissionName(PermissionName.QUIZ_UPDATE.name());
        quizUpdatePermission = permissionRepository.save(quizUpdatePermission);

        Permission categoryReadPermission = new Permission();
        categoryReadPermission.setPermissionName(PermissionName.CATEGORY_READ.name());
        categoryReadPermission = permissionRepository.save(categoryReadPermission);

        Permission tagReadPermission = new Permission();
        tagReadPermission.setPermissionName(PermissionName.TAG_READ.name());
        tagReadPermission = permissionRepository.save(tagReadPermission);

        // Create roles
        Role adminRole = new Role();
        adminRole.setRoleName(RoleName.ROLE_ADMIN.name());
        adminRole.setPermissions(Set.of(quizCreatePermission, quizReadPermission, quizUpdatePermission, categoryReadPermission, tagReadPermission));
        adminRole = roleRepository.save(adminRole);

        // Create user with role
        User user = new User();
        user.setUsername("admin");
        user.setEmail("admin@example.com");
        user.setHashedPassword("pw");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        user.setRoles(Set.of(adminRole));
        User savedUser = userRepository.save(user);

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
                        .param("category", "Science")
                        .param("scope", "me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].title", is("Advanced Math")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes filters by tag names")
    void filterByTagNames() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("tag", "Java")
                        .param("scope", "me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", everyItem(anyOf(containsString("Java"), is("Mixed Bag")))));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes filters by author username")
    void filterByAuthorName() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("authorName", "admin")
                        .param("scope", "me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes full-text search across title/description")
    void fullTextSearch() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("search", "java")
                        .param("scope", "me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", everyItem(anyOf(containsString("Java"), is("Mixed Bag")))));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes difficulty exact match")
    void difficultyMatch() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("difficulty", "HARD")
                        .param("scope", "me"))
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
                        .param("difficulty", "MEDIUM")
                        .param("scope", "me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].title", is("Mixed Bag")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes multiple values for category/tag")
    void multipleValues_categoryAndTag() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("category", "General", "Science")
                        .param("tag", "Java", "Math")
                        .param("scope", "me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes invalid difficulty -> 400 Bad Request")
    void invalidDifficulty_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("difficulty", "IMPOSSIBLE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}


