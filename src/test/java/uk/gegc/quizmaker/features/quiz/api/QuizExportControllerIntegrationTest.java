package uk.gegc.quizmaker.features.quiz.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import jakarta.persistence.EntityManager;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
@DisplayName("Quiz Export Controller Integration Tests")
class QuizExportControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private EntityManager em;
    @Autowired private ObjectMapper objectMapper;

    // Scope: public (anonymous access)

    @Test
    @DisplayName("public scope: anonymous access returns 200 with only PUBLIC+PUBLISHED quizzes")
    void exportPublicScope_anonymous_returns200WithPublicPublishedOnly() throws Exception {
        // Given
        User author = createTestUser("pub_author_" + UUID.randomUUID(), "pub@test.com", PermissionName.QUIZ_READ);
        Quiz publicPublished = createQuiz("Public Published", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        Quiz publicDraft = createQuiz("Public Draft", Visibility.PUBLIC, QuizStatus.DRAFT, author);
        Quiz privateListed = createQuiz("Private Listed", Visibility.PRIVATE, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"quizzes_public_")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.isArray()).isTrue();
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Public Published");
    }

    @Test
    @DisplayName("public scope: default scope when not specified")
    void exportNoScope_defaultsToPublic() throws Exception {
        // Given
        User author = createTestUser("default_author_" + UUID.randomUUID(), "default@test.com", PermissionName.QUIZ_READ);
        createQuiz("Public Published", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"quizzes_public_")));
    }

    // Scope: me (authenticated access)

    @Test
    @DisplayName("scope=me: anonymous returns 401")
    void exportMeScope_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("scope=me: authenticated without QUIZ_READ returns 403")
    void exportMeScope_withoutPermission_returns403() throws Exception {
        // Given
        String username = "noperm_" + UUID.randomUUID();
        User userWithoutPermission = createUserWithoutPermissions(username, username + "@test.com");
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .with(user(username)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("scope=me: authenticated with QUIZ_READ returns 200 with only author's quizzes")
    void exportMeScope_withPermission_returns200WithAuthorQuizzesOnly() throws Exception {
        // Given
        String username1 = "author1_" + UUID.randomUUID();
        String username2 = "author2_" + UUID.randomUUID();
        User testAuthor = createTestUser(username1, username1 + "@test.com", PermissionName.QUIZ_READ);
        User otherAuthor = createTestUser(username2, username2 + "@test.com", PermissionName.QUIZ_READ);
        
        createQuiz("Author's Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, testAuthor);
        createQuiz("Other's Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, otherAuthor);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .with(user(username1)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("quizzes_me_")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.isArray()).isTrue();
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Author's Quiz");
    }

    @Test
    @DisplayName("scope=me: includes author's PRIVATE and DRAFT quizzes")
    void exportMeScope_includesPrivateAndDraftQuizzes() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        createQuiz("Private Draft", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        createQuiz("Private Published", Visibility.PRIVATE, QuizStatus.PUBLISHED, author);
        createQuiz("Public Draft", Visibility.PUBLIC, QuizStatus.DRAFT, author);
        createQuiz("Public Published", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then - scope=me should include ALL statuses and visibilities for author
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .with(user(username)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(4); // All 4 quizzes regardless of status/visibility
    }

    @Test
    @DisplayName("scope=me: excludes other authors' PUBLIC+PUBLISHED quizzes")
    void exportMeScope_excludesOtherAuthorsPublicQuizzes() throws Exception {
        // Given
        String username1 = "author1_" + UUID.randomUUID();
        String username2 = "author2_" + UUID.randomUUID();
        User author1 = createTestUser(username1, username1 + "@test.com", PermissionName.QUIZ_READ);
        User author2 = createTestUser(username2, username2 + "@test.com", PermissionName.QUIZ_READ);
        
        createQuiz("My Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, author1);
        createQuiz("Other Public", Visibility.PUBLIC, QuizStatus.PUBLISHED, author2);
        em.flush();

        // When & Then - should NOT include other author's public quiz
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .with(user(username1)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("My Quiz");
    }

    @Test
    @DisplayName("scope=me: works with JSON_EDITABLE format")
    void exportMeScope_jsonFormat_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("My Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .with(user(username)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition", containsString(".json")));
    }

    @Test
    @DisplayName("scope=me: works with XLSX_EDITABLE format")
    void exportMeScope_xlsxFormat_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("My Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "XLSX_EDITABLE")
                        .param("scope", "me")
                        .with(user(username)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")));
    }

    @Test
    @DisplayName("scope=me: works with HTML_PRINT format")
    void exportMeScope_htmlFormat_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("My Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "HTML_PRINT")
                        .param("scope", "me")
                        .with(user(username)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Content-Disposition", containsString(".html")));
    }

    @Test
    @DisplayName("scope=me: works with PDF_PRINT format")
    void exportMeScope_pdfFormat_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("My Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "PDF_PRINT")
                        .param("scope", "me")
                        .with(user(username)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString(".pdf")));
    }

    @Test
    @DisplayName("scope=me: combined with categoryIds filter")
    void exportMeScope_withCategoryFilter_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        Category cat1 = new Category();
        cat1.setName("Category1_" + UUID.randomUUID());
        categoryRepository.save(cat1);
        
        Category cat2 = new Category();
        cat2.setName("Category2_" + UUID.randomUUID());
        categoryRepository.save(cat2);
        
        Quiz quiz1 = createQuizWithCategory("Quiz in Cat1", Visibility.PRIVATE, QuizStatus.DRAFT, author, cat1);
        Quiz quiz2 = createQuizWithCategory("Quiz in Cat2", Visibility.PRIVATE, QuizStatus.DRAFT, author, cat2);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .param("categoryIds", cat1.getId().toString())
                        .with(user(username)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Quiz in Cat1");
    }

    @Test
    @DisplayName("scope=me: combined with tags filter")
    void exportMeScope_withTagsFilter_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        int random = (int)(Math.random() * 1000000);
        Tag tag1 = new Tag();
        tag1.setName("tag1_" + random);
        tagRepository.save(tag1);
        
        Tag tag2 = new Tag();
        tag2.setName("tag2_" + (random + 999));
        tagRepository.save(tag2);
        
        createQuizWithTag("Quiz with Tag1", Visibility.PRIVATE, QuizStatus.DRAFT, author, tag1);
        createQuizWithTag("Quiz with Tag2", Visibility.PRIVATE, QuizStatus.DRAFT, author, tag2);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .param("tags", tag1.getName())
                        .with(user(username)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Quiz with Tag1");
    }

    @Test
    @DisplayName("scope=me: combined with difficulty filter")
    void exportMeScope_withDifficultyFilter_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        createQuizWithDifficulty("Easy Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, author, Difficulty.EASY);
        createQuizWithDifficulty("Hard Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, author, Difficulty.HARD);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .param("difficulty", "HARD")
                        .with(user(username)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Hard Quiz");
    }

    @Test
    @DisplayName("scope=me: combined with search filter")
    void exportMeScope_withSearchFilter_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        createQuiz("Advanced Java Concepts", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        createQuiz("Basic Python", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .param("search", "java")
                        .with(user(username)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Advanced Java Concepts");
    }

    @Test
    @DisplayName("scope=me: combined with quizIds filter")
    void exportMeScope_withQuizIdsFilter_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        Quiz quiz1 = createQuiz("Quiz 1", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        Quiz quiz2 = createQuiz("Quiz 2", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        Quiz quiz3 = createQuiz("Quiz 3", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .param("quizIds", quiz1.getId().toString() + "," + quiz3.getId().toString())
                        .with(user(username)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("scope=me: returns empty array when author has no quizzes")
    void exportMeScope_noQuizzes_returnsEmptyArray() throws Exception {
        // Given
        String username = "lonely_author_" + UUID.randomUUID();
        createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .with(user(username)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.isArray()).isTrue();
        assertThat(quizzes.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("scope=me: handles many quizzes from author")
    void exportMeScope_manyQuizzes_works() throws Exception {
        // Given
        String username = "prolific_author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        // Create 10 quizzes
        for (int i = 0; i < 10; i++) {
            createQuiz("Quiz " + i, Visibility.PRIVATE, QuizStatus.DRAFT, author);
        }
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .with(user(username)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(10);
    }

    @Test
    @DisplayName("scope=me: with print options accepted")
    void exportMeScope_withPrintOptions_works() throws Exception {
        // Given
        String username = "author_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("My Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "HTML_PRINT")
                        .param("scope", "me")
                        .param("includeCover", "true")
                        .param("includeMetadata", "true")
                        .param("includeHints", "true")
                        .with(user(username)))
                .andExpect(status().isOk());
    }

    // Scope: all (admin/moderator access)

    @Test
    @DisplayName("scope=all: without QUIZ_MODERATE or QUIZ_ADMIN returns 403")
    void exportAllScope_withoutModeratePermission_returns403() throws Exception {
        // Given
        String username = "reader_" + UUID.randomUUID();
        createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        em.flush();
        
        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "all")
                        .with(user(username))) // Has QUIZ_READ only
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("scope=all: with QUIZ_MODERATE returns 200 with all quizzes")
    void exportAllScope_withModeratePermission_returns200WithAllQuizzes() throws Exception {
        // Given
        String modUsername = "mod_" + UUID.randomUUID();
        String author1Username = "a1_" + UUID.randomUUID();
        String author2Username = "a2_" + UUID.randomUUID();
        
        User moderator = createTestUser(modUsername, modUsername + "@test.com", PermissionName.QUIZ_MODERATE);
        User author1 = createTestUser(author1Username, author1Username + "@test.com", PermissionName.QUIZ_READ);
        User author2 = createTestUser(author2Username, author2Username + "@test.com", PermissionName.QUIZ_READ);
        
        createQuiz("Public Published", Visibility.PUBLIC, QuizStatus.PUBLISHED, author1);
        createQuiz("Private Draft", Visibility.PRIVATE, QuizStatus.DRAFT, author2);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "all")
                        .with(user(modUsername)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("quizzes_all_")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.isArray()).isTrue();
        assertThat(quizzes.size()).isEqualTo(2);
    }

    // Filters: categoryIds

    @Test
    @DisplayName("filter: categoryIds returns only quizzes in specified categories")
    void exportWithCategoryFilter_returnsMatchingQuizzes() throws Exception {
        // Given
        String username = "catfilter_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        Category programmingCategory = new Category();
        programmingCategory.setName("Programming_" + UUID.randomUUID());
        categoryRepository.save(programmingCategory);
        
        Category scienceCategory = new Category();
        scienceCategory.setName("Science_" + UUID.randomUUID());
        categoryRepository.save(scienceCategory);
        
        Quiz progQuiz = createQuizWithCategory("Java Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author, programmingCategory);
        Quiz sciQuiz = createQuizWithCategory("Physics Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author, scienceCategory);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public")
                        .param("categoryIds", programmingCategory.getId().toString()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Java Quiz");
    }

    // Filters: tags

    @Test
    @DisplayName("filter: tag returns quizzes with matching tag")
    void exportWithTagFilter_returnsMatchingQuizzes() throws Exception {
        // Given
        String username = "tagfilter_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        // Use distinct random suffixes
        int random = (int)(Math.random() * 1000000);
        Tag javaTag = new Tag();
        javaTag.setName("jtag" + random);
        tagRepository.save(javaTag);
        
        Tag pythonTag = new Tag();
        pythonTag.setName("ptag" + (random + 123456));
        tagRepository.save(pythonTag);
        
        Quiz javaQuiz = createQuizWithTag("Java Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author, javaTag);
        Quiz pythonQuiz = createQuizWithTag("Python Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author, pythonTag);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public")
                        .param("tags", javaTag.getName())) // Use 'tags' to match DTO field
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        // Should return only the quiz with the matching tag
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Java Quiz");
    }

    // Filters: difficulty

    @Test
    @DisplayName("filter: difficulty returns only quizzes with matching difficulty")
    void exportWithDifficultyFilter_returnsMatchingQuizzes() throws Exception {
        // Given
        String username = "diffilter_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        Quiz easyQuiz = createQuizWithDifficulty("Easy Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author, Difficulty.EASY);
        Quiz hardQuiz = createQuizWithDifficulty("Hard Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author, Difficulty.HARD);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public")
                        .param("difficulty", "HARD"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Hard Quiz");
    }

    // Filters: search

    @Test
    @DisplayName("filter: search matches title or description (case-insensitive)")
    void exportWithSearchFilter_returnsMatchingQuizzes() throws Exception {
        // Given
        String username = "searchfilter_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        createQuiz("Java Advanced Topics", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        createQuiz("Python Basics", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then - search in title
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public")
                        .param("search", "advanced")) // Case-insensitive
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Java Advanced Topics");
    }

    // Filters: quizIds

    @Test
    @DisplayName("filter: quizIds returns only quizzes with specified IDs")
    void exportWithQuizIdsFilter_returnsMatchingQuizzes() throws Exception {
        // Given
        String username = "idsfilter_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        Quiz quiz1 = createQuiz("Quiz 1", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        Quiz quiz2 = createQuiz("Quiz 2", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        Quiz quiz3 = createQuiz("Quiz 3", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public")
                        .param("quizIds", quiz1.getId().toString() + "," + quiz3.getId().toString()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(2);
    }

    // Multiple filters combined

    @Test
    @DisplayName("filter: multiple filters combined work correctly")
    void exportWithMultipleFilters_returnsMatchingQuizzes() throws Exception {
        // Given
        String username = "multifilter_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        
        Category programmingCategory = new Category();
        programmingCategory.setName("Programming_" + UUID.randomUUID());
        categoryRepository.save(programmingCategory);
        
        int random = (int)(Math.random() * 1000000);
        Tag javaTag = new Tag();
        javaTag.setName("jtag" + random);
        tagRepository.save(javaTag);
        
        Tag pythonTag = new Tag();
        pythonTag.setName("ptag" + (random + 123456));
        tagRepository.save(pythonTag);
        
        createQuizFull("Java Advanced", Visibility.PUBLIC, QuizStatus.PUBLISHED, author, programmingCategory, Difficulty.HARD, javaTag);
        createQuizFull("Python Basics", Visibility.PUBLIC, QuizStatus.PUBLISHED, author, programmingCategory, Difficulty.EASY, pythonTag);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public")
                        .param("categoryIds", programmingCategory.getId().toString())
                        .param("tags", javaTag.getName())
                        .param("difficulty", "HARD"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.size()).isEqualTo(1);
        assertThat(quizzes.get(0).get("title").asText()).isEqualTo("Java Advanced");
    }

    // Different formats

    @Test
    @DisplayName("format: JSON_EDITABLE returns application/json with .json extension")
    void exportJsonFormat_correctContentTypeAndExtension() throws Exception {
        // Given
        String username = "json_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("Test Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition", containsString(".json")));
    }

    @Test
    @DisplayName("format: XLSX_EDITABLE returns correct content type with .xlsx extension")
    void exportXlsxFormat_correctContentTypeAndExtension() throws Exception {
        // Given
        String username = "xlsx_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("Test Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "XLSX_EDITABLE")
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")));
    }

    @Test
    @DisplayName("format: HTML_PRINT returns text/html with .html extension")
    void exportHtmlFormat_correctContentTypeAndExtension() throws Exception {
        // Given
        String username = "html_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("Test Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "HTML_PRINT")
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Content-Disposition", containsString(".html")));
    }

    @Test
    @DisplayName("format: PDF_PRINT returns application/pdf with .pdf extension")
    void exportPdfFormat_correctContentTypeAndExtension() throws Exception {
        // Given
        String username = "pdf_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("Test Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "PDF_PRINT")
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString(".pdf")));
    }

    // PrintOptions tests

    @Test
    @DisplayName("printOptions: includeCover parameter is accepted")
    void exportWithIncludeCover_accepted() throws Exception {
        // Given
        String username = "cover_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("Test Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "HTML_PRINT")
                        .param("scope", "public")
                        .param("includeCover", "true"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("printOptions: all print parameters are accepted for print formats")
    void exportWithAllPrintOptions_accepted() throws Exception {
        // Given
        String username = "printopts_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("Test Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "PDF_PRINT")
                        .param("scope", "public")
                        .param("includeCover", "true")
                        .param("includeMetadata", "true")
                        .param("answersOnSeparatePages", "true")
                        .param("includeHints", "true")
                        .param("includeExplanations", "true")
                        .param("groupQuestionsByType", "true"))
                .andExpect(status().isOk());
    }

    // Validation and error handling

    @Test
    @DisplayName("validation: invalid format enum returns 400")
    void exportWithInvalidFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "INVALID_FORMAT")
                        .param("scope", "public"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("validation: invalid UUID in quizIds returns 400")
    void exportWithInvalidQuizId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public")
                        .param("quizIds", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("validation: invalid UUID in categoryIds returns 400")
    void exportWithInvalidCategoryId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public")
                        .param("categoryIds", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    // Streaming behavior

    @Test
    @DisplayName("streaming: response contains content")
    void export_streamsContent() throws Exception {
        // Given
        String username = "stream_" + UUID.randomUUID();
        User author = createTestUser(username, username + "@test.com", PermissionName.QUIZ_READ);
        createQuiz("Test Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, author);
        em.flush();

        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] content = result.getResponse().getContentAsByteArray();
        assertThat(content.length).isGreaterThan(0);
    }

    // Edge cases

    @Test
    @DisplayName("edge case: no matching quizzes returns empty array")
    void exportWithNoMatches_returnsEmptyArray() throws Exception {
        // When & Then
        MvcResult result = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode quizzes = objectMapper.readTree(body);
        
        assertThat(quizzes.isArray()).isTrue();
        assertThat(quizzes.size()).isEqualTo(0);
    }

    // Helper Methods

    private User createTestUser(String username, String email, PermissionName... permissionNames) {
        // Fetch existing permissions
        java.util.Set<Permission> permissions = new java.util.HashSet<>();
        if (permissionNames.length > 0) {
            permissions.add(em.createQuery(
                    "SELECT p FROM Permission p WHERE p.permissionName = :name", Permission.class)
                    .setParameter("name", permissionNames[0].name())
                    .getSingleResult());
        }

        Role role = Role.builder()
                .roleName("ROLE_" + username.toUpperCase())
                .permissions(permissions)
                .build();
        em.persist(role);

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setHashedPassword("hashed_password");
        user.setActive(true);
        user.setDeleted(false);
        user.setRoles(new java.util.HashSet<>(Set.of(role)));
        userRepository.save(user);
        
        return user;
    }

    private User createUserWithoutPermissions(String username, String email) {
        Role role = Role.builder()
                .roleName("ROLE_" + username.toUpperCase())
                .permissions(new java.util.HashSet<>())
                .build();
        em.persist(role);

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setHashedPassword("hashed_password");
        user.setActive(true);
        user.setDeleted(false);
        user.setRoles(new java.util.HashSet<>(Set.of(role)));
        userRepository.save(user);
        
        return user;
    }

    private Quiz createQuiz(String title, Visibility visibility, QuizStatus status, User creator) {
        // Create a category for this quiz
        Category category = new Category();
        category.setName("Cat_" + UUID.randomUUID());
        categoryRepository.save(category);
        
        Quiz quiz = new Quiz();
        quiz.setTitle(title);
        quiz.setDescription("Description for " + title);
        quiz.setVisibility(visibility);
        quiz.setStatus(status);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setEstimatedTime(10);
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        
        // Add a simple question to make the quiz valid
        Question q = new Question();
        q.setType(QuestionType.OPEN);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Sample question");
        q.setContent("{\"answer\":\"Sample answer\"}");
        quiz.setQuestions(Set.of(q));
        
        return quizRepository.save(quiz);
    }

    private Quiz createQuizWithCategory(String title, Visibility visibility, QuizStatus status, User creator, Category category) {
        Quiz quiz = new Quiz();
        quiz.setTitle(title);
        quiz.setDescription("Description for " + title);
        quiz.setVisibility(visibility);
        quiz.setStatus(status);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setEstimatedTime(10);
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        
        Question q = new Question();
        q.setType(QuestionType.OPEN);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Sample question");
        q.setContent("{\"answer\":\"Sample answer\"}");
        quiz.setQuestions(Set.of(q));
        
        return quizRepository.save(quiz);
    }

    private Quiz createQuizWithTag(String title, Visibility visibility, QuizStatus status, User creator, Tag tag) {
        // Create a category for this quiz
        Category category = new Category();
        category.setName("Cat_" + UUID.randomUUID());
        categoryRepository.save(category);
        
        Quiz quiz = new Quiz();
        quiz.setTitle(title);
        quiz.setDescription("Description for " + title);
        quiz.setVisibility(visibility);
        quiz.setStatus(status);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setEstimatedTime(10);
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setTags(Set.of(tag));
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);

        Question q = new Question();
        q.setType(QuestionType.OPEN);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Sample question");
        q.setContent("{\"answer\":\"Sample answer\"}");
        quiz.setQuestions(Set.of(q));
        
        return quizRepository.save(quiz);
    }

    private Quiz createQuizWithDifficulty(String title, Visibility visibility, QuizStatus status, User creator, Difficulty difficulty) {
        // Create a category for this quiz
        Category category = new Category();
        category.setName("Cat_" + UUID.randomUUID());
        categoryRepository.save(category);
        
        Quiz quiz = new Quiz();
        quiz.setTitle(title);
        quiz.setDescription("Description for " + title);
        quiz.setVisibility(visibility);
        quiz.setStatus(status);
        quiz.setDifficulty(difficulty);
        quiz.setEstimatedTime(10);
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        
        Question q = new Question();
        q.setType(QuestionType.OPEN);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Sample question");
        q.setContent("{\"answer\":\"Sample answer\"}");
        quiz.setQuestions(Set.of(q));
        
        return quizRepository.save(quiz);
    }

    private Quiz createQuizFull(String title, Visibility visibility, QuizStatus status, User creator, 
                                Category category, Difficulty difficulty, Tag tag) {
        Quiz quiz = new Quiz();
        quiz.setTitle(title);
        quiz.setDescription("Description for " + title);
        quiz.setVisibility(visibility);
        quiz.setStatus(status);
        quiz.setDifficulty(difficulty);
        quiz.setEstimatedTime(10);
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setTags(Set.of(tag));
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        
        Question q = new Question();
        q.setType(QuestionType.OPEN);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Sample question");
        q.setContent("{\"answer\":\"Sample answer\"}");
        quiz.setQuestions(Set.of(q));
        
        return quizRepository.save(quiz);
    }

    // ============================================
    // SECURITY TESTS - P0 and P1 Vulnerabilities
    // ============================================

    @Test
    @DisplayName("Security P0: Anonymous user cannot export private quiz by ID")
    void security_anonymousCannotExportPrivateQuizById() throws Exception {
        // Create a private quiz
        User testUser = createTestUser("security_user1_" + UUID.randomUUID(), "sec1@test.com", PermissionName.QUIZ_READ);
        Quiz privateQuiz = createQuiz("Private Quiz", Visibility.PRIVATE, QuizStatus.PUBLISHED, testUser);
        em.flush();
        
        // Anonymous user tries to export by quiz ID
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("quizIds", privateQuiz.getId().toString())
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    JsonNode quizzes = objectMapper.readTree(json);
                    // Should return empty array - private quiz filtered out
                    assertThat(quizzes.isArray()).isTrue();
                    assertThat(quizzes.size()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("Security P0: Anonymous user cannot export unpublished quiz by ID")
    void security_anonymousCannotExportUnpublishedQuizById() throws Exception {
        // Create an unpublished but public quiz
        User testUser = createTestUser("security_user2_" + UUID.randomUUID(), "sec2@test.com", PermissionName.QUIZ_READ);
        Quiz unpublishedQuiz = createQuiz("Draft Quiz", Visibility.PUBLIC, QuizStatus.DRAFT, testUser);
        em.flush();
        
        // Anonymous user tries to export by quiz ID
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("quizIds", unpublishedQuiz.getId().toString())
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    JsonNode quizzes = objectMapper.readTree(json);
                    // Should return empty array - unpublished quiz filtered out
                    assertThat(quizzes.isArray()).isTrue();
                    assertThat(quizzes.size()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("Security P0: Anonymous user can export public published quiz by ID")
    void security_anonymousCanExportPublicPublishedQuizById() throws Exception {
        // Create a public published quiz
        User testUser = createTestUser("security_user3_" + UUID.randomUUID(), "sec3@test.com", PermissionName.QUIZ_READ);
        Quiz publicQuiz = createQuiz("Public Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED, testUser);
        em.flush();
        
        // Anonymous user tries to export by quiz ID
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("quizIds", publicQuiz.getId().toString())
                        .param("scope", "public"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    JsonNode quizzes = objectMapper.readTree(json);
                    // Should return the quiz
                    assertThat(quizzes.isArray()).isTrue();
                    assertThat(quizzes.size()).isEqualTo(1);
                    assertThat(quizzes.get(0).get("id").asText()).isEqualTo(publicQuiz.getId().toString());
                });
    }

    @Test
    @DisplayName("Security P0: User with QUIZ_READ cannot export other user's private quiz by ID using scope=me")
    void security_userCannotExportOtherUsersPrivateQuizByIdWithScopeMe() throws Exception {
        // Create users
        String user1Name = "security_user4_" + UUID.randomUUID();
        String user2Name = "security_user5_" + UUID.randomUUID();
        User user1 = createTestUser(user1Name, "sec4@test.com", PermissionName.QUIZ_READ);
        User user2 = createTestUser(user2Name, "sec5@test.com", PermissionName.QUIZ_READ);
        
        // Create a private quiz for user1
        Quiz user1PrivateQuiz = createQuiz("User1 Private", Visibility.PRIVATE, QuizStatus.PUBLISHED, user1);
        em.flush();
        
        // User2 tries to export user1's quiz with scope=me
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .with(user(user2Name))
                        .param("format", "JSON_EDITABLE")
                        .param("quizIds", user1PrivateQuiz.getId().toString())
                        .param("scope", "me"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    JsonNode quizzes = objectMapper.readTree(json);
                    // Should return empty array - quiz doesn't belong to user2
                    assertThat(quizzes.isArray()).isTrue();
                    assertThat(quizzes.size()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("Security P1: User cannot use scope=me with another user's authorId")
    void security_userCannotUseScopeMeWithOtherAuthorId() throws Exception {
        // Create users
        String user1Name = "security_user6_" + UUID.randomUUID();
        String user2Name = "security_user7_" + UUID.randomUUID();
        User user1 = createTestUser(user1Name, "sec6@test.com", PermissionName.QUIZ_READ);
        User user2 = createTestUser(user2Name, "sec7@test.com", PermissionName.QUIZ_READ);
        
        // Create a private quiz for user1
        createQuiz("User1 Private", Visibility.PRIVATE, QuizStatus.PUBLISHED, user1);
        em.flush();
        
        // User2 tries to export with scope=me but user1's authorId
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .with(user(user2Name))
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .param("authorId", user1.getId().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security P1: scope=me always uses authenticated user's ID regardless of authorId param")
    void security_scopeMeUsesAuthenticatedUserId() throws Exception {
        // Create users
        String user1Name = "security_user8_" + UUID.randomUUID();
        String user2Name = "security_user9_" + UUID.randomUUID();
        User user1 = createTestUser(user1Name, "sec8@test.com", PermissionName.QUIZ_READ);
        User user2 = createTestUser(user2Name, "sec9@test.com", PermissionName.QUIZ_READ);
        
        // Create quizzes for both users
        Quiz user1Quiz = createQuiz("User1 Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, user1);
        Quiz user2Quiz = createQuiz("User2 Quiz", Visibility.PRIVATE, QuizStatus.DRAFT, user2);
        em.flush();
        
        // User2 requests with scope=me (their own quizzes)
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .with(user(user2Name))
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    JsonNode quizzes = objectMapper.readTree(json);
                    // Should only return user2's quiz
                    assertThat(quizzes.isArray()).isTrue();
                    assertThat(quizzes.size()).isEqualTo(1);
                    assertThat(quizzes.get(0).get("id").asText()).isEqualTo(user2Quiz.getId().toString());
                });
    }

    @Test
    @DisplayName("Security: User can export their own quiz by ID with scope=me")
    void security_userCanExportOwnQuizByIdWithScopeMe() throws Exception {
        // Create user
        String user1Name = "security_user10_" + UUID.randomUUID();
        User user1 = createTestUser(user1Name, "sec10@test.com", PermissionName.QUIZ_READ);
        
        // Create a private quiz for user1
        Quiz user1Quiz = createQuiz("User1 Private", Visibility.PRIVATE, QuizStatus.DRAFT, user1);
        em.flush();
        
        // User1 exports their own quiz by ID
        mockMvc.perform(get("/api/v1/quizzes/export")
                        .with(user(user1Name))
                        .param("format", "JSON_EDITABLE")
                        .param("quizIds", user1Quiz.getId().toString())
                        .param("scope", "me"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    JsonNode quizzes = objectMapper.readTree(json);
                    // Should return their quiz
                    assertThat(quizzes.isArray()).isTrue();
                    assertThat(quizzes.size()).isEqualTo(1);
                    assertThat(quizzes.get(0).get("id").asText()).isEqualTo(user1Quiz.getId().toString());
                });
    }
}
