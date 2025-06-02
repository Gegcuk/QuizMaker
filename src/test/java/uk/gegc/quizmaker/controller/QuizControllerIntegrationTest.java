package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.attempt.AnswerSubmissionRequest;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.dto.quiz.UpdateQuizRequest;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.quiz.Visibility;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.attempt.AttemptRepository;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@DirtiesContext(classMode = AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create"
})
@WithMockUser(username = "defaultUser", roles = "ADMIN")
@DisplayName("Integration Tests QuizController")
class QuizControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TagRepository tagRepository;
    @Autowired QuestionRepository questionRepository;
    @Autowired QuizRepository quizRepository;
    @Autowired
    AttemptRepository attemptRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private UUID categoryId;
    private UUID tagId;
    private UUID questionId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM answers");
        jdbcTemplate.execute("DELETE FROM attempts");
        jdbcTemplate.execute("DELETE FROM quiz_questions");
        jdbcTemplate.execute("DELETE FROM quiz_tags");
        jdbcTemplate.execute("DELETE FROM question_tags");
        jdbcTemplate.execute("DELETE FROM quizzes");

        questionRepository.deleteAllInBatch();
        tagRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        User defaultUser = new User();
        defaultUser.setUsername("defaultUser");
        defaultUser.setEmail("def@ex.com");
        defaultUser.setHashedPassword("pw");
        defaultUser.setActive(true);
        defaultUser.setDeleted(false);
        userRepository.save(defaultUser);

        Category c = new Category();
        c.setName("General");
        c.setDescription("Default");
        categoryRepository.save(c);
        categoryId = c.getId();

        Tag t = new Tag();
        t.setName("tag-one");
        t.setDescription("desc");
        tagRepository.save(t);
        tagId = t.getId();

        Question q = new Question();
        q.setType(QuestionType.MCQ_SINGLE);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("What?");
        q.setContent("{\"options\":[\"A\",\"B\"]}");
        q.setHint("hint");
        q.setExplanation("explanation");
        questionRepository.save(q);
        questionId = q.getId();
    }

    @Test
    @DisplayName("Full CRUD and associations flow as ADMIN → succeeds")
    void fullQuizCrudAndAssociations() throws Exception {
        // --- 1) Create a quiz ---
        CreateQuizRequest create = new CreateQuizRequest(
                "My Quiz", "desc",
                null, null,
                false, false,
                10, 5,
                categoryId,
                List.of(tagId)
        );
        String createJson = objectMapper.writeValueAsString(create);

        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quizId").exists())
                .andReturn().getResponse().getContentAsString();

        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        // --- 2) GET list, should contain 1 ---
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)));

        // --- 3) GET by id ---
        mockMvc.perform(get("/api/v1/quizzes/{id}", quizId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(quizId.toString())))
                .andExpect(jsonPath("$.title", is("My Quiz")))
                .andExpect(jsonPath("$.tagIds[0]", is(tagId.toString())));

        // --- 4) PATCH update title ---
        UpdateQuizRequest update = new UpdateQuizRequest(
                "New Title",
                null, null, null,
                null, null,
                15, 3,
                null, null
        );
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("New Title")))
                .andExpect(jsonPath("$.estimatedTime", is(15)))
                .andExpect(jsonPath("$.timerDuration", is(3)));

        // --- 5) Add and remove question ---
        mockMvc.perform(post("/api/v1/quizzes/{q}/questions/{ques}", quizId, questionId))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/quizzes/{q}/questions/{ques}", quizId, questionId))
                .andExpect(status().isNoContent());

        // --- 6) Add and remove tag ---
        mockMvc.perform(post("/api/v1/quizzes/{q}/tags/{t}", quizId, tagId))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/quizzes/{q}/tags/{t}", quizId, tagId))
                .andExpect(status().isNoContent());

        // --- 7) Change category ---
        Category c2 = new Category();
        c2.setName("Other");
        c2.setDescription("other");
        categoryRepository.save(c2);

        mockMvc.perform(patch("/api/v1/quizzes/{q}/category/{c}", quizId, c2.getId()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/quizzes/{id}", quizId))
                .andExpect(jsonPath("$.categoryId", is(c2.getId().toString())));

        // --- 8) Delete quiz ---
        mockMvc.perform(delete("/api/v1/quizzes/{id}", quizId))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/quizzes/{id}", quizId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with only required fields -> returns 201 CREATED")
    void createQuiz_requiredFields_adminReturns201() throws Exception {
        String json = """
        {
          "title":"Quick Quiz",
          "estimatedTime":10,
          "timerDuration":5,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quizId").exists());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes missing title -> returns 400 BAD_REQUEST")
    void createQuiz_missingTitle_returns400() throws Exception {
        String json = """
        {
          "estimatedTime":10,
          "timerDuration":5,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title must not be blank"))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes title too short returns 400 BAD_REQUEST")
    void createQuiz_titleTooShort_returns400() throws Exception {
        String json = """
        {
          "title":"ab",
          "estimatedTime":10,
          "timerDuration":5,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title length must be between 3 and 100 characters"))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes title too long returns 400 BAD_REQUEST")
    void createQuiz_titleTooLong_returns400() throws Exception {
        String longTitle = "x".repeat(101);
        String json = """
        {
          "title":"%s",
          "estimatedTime":10,
          "timerDuration":5,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(longTitle, categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title length must be between 3 and 100 characters"))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with description too long -> returns 400 BAD_REQUEST")
    void createQuiz_descriptionTooLong_returns400() throws Exception {
        String longDesc = "d".repeat(1001);
        String json = """
        {
          "title":"Valid Quiz",
          "description":"%s",
          "estimatedTime":10,
          "timerDuration":5,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(longDesc, categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Description must be at most 1000 characters long"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with estimatedTime < 1 -> returns 400 BAD_REQUEST")
    void createQuiz_estimatedTimeTooLow_returns400() throws Exception {
        String json = """
        {
          "title":"Valid Quiz",
          "estimatedTime":0,
          "timerDuration":5,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Estimated time can't be less than 1 minute"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with estimatedTime > 180 -> returns 400 BAD_REQUEST")
    void createQuiz_estimatedTimeTooHigh_returns400() throws Exception {
        String json = """
        {
          "title":"Valid Quiz",
          "estimatedTime":181,
          "timerDuration":5,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Estimated time can't be more than 180 minutes"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with timerDuration < 1 ->  returns 400 BAD_REQUEST")
    void createQuiz_timerDurationTooLow_returns400() throws Exception {
        String json = """
        {
          "title":"Valid Quiz",
          "estimatedTime":10,
          "timerDuration":0,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Timer duration must be at least 1 minute"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with timerDuration > 180 -> returns 400 BAD_REQUEST")
    void createQuiz_timerDurationTooHigh_returns400() throws Exception {
        String json = """
        {
          "title":"Valid Quiz",
          "estimatedTime":10,
          "timerDuration":181,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Timer duration must be at most 180 minutes"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with unknown tagId -> returns 404 NOT_FOUND")
    void createQuiz_unknownTagId_returns404() throws Exception {
        UUID badTag = UUID.randomUUID();
        String json = """
        {
          "title":"Valid Quiz",
          "estimatedTime":10,
          "timerDuration":5,
          "categoryId":"%s",
          "tagIds":["%s"],
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(categoryId, badTag);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Tag " + badTag + " not found"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes anonymous -> returns 401 UNAUTHORIZED")
    void createQuiz_anonymous_returns401() throws Exception {
        String json = """
        {
          "title":"Quiz",
          "estimatedTime":10,
          "timerDuration":5,
          "categoryId":"%s",
          "isRepetitionEnabled":false,
          "timerEnabled":false
        }
        """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes -> returns empty page when no quizzes present")
    void listQuizzes_emptyDb_returnsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes?page=1&size=5 -> returns correct page and size")
    void listQuizzes_paginationAndSorting() throws Exception {
        for (int i = 1; i <= 12; i++) {
            CreateQuizRequest req = new CreateQuizRequest(
                    "Quiz " + i,
                    "desc",
                    null, null,
                    false, false,
                    10, 5,
                    categoryId,
                    List.of(tagId)
            );
            mockMvc.perform(post("/api/v1/quizzes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/quizzes")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(12)))
                .andExpect(jsonPath("$.size", is(5)))
                .andExpect(jsonPath("$.number", is(1)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes with negative page -> returns 200 with default size")
    void listQuizzes_negativePage_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("page", "-1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.size", is(5)))
                .andExpect(jsonPath("$.number", is(0)))
                .andExpect(jsonPath("$.content", hasSize(0)));;
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{id} with non existing id -> returns 404 NOT FOUND")
    void getNonexistingQuiz_returns404() throws Exception{
        UUID dummyId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/quizzes/{id}", dummyId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with all fields returns -> 200 OK with updated JSON")
    void updateQuiz_allFields_adminReturns200() throws Exception {
        CreateQuizRequest create = new CreateQuizRequest(
                "Original Title", "Original desc",
                Visibility.PUBLIC, Difficulty.HARD,
                true, true,
                20, 10,
                categoryId,
                List.of(tagId)
        );
        String createJson = objectMapper.writeValueAsString(create);
        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        UpdateQuizRequest update = new UpdateQuizRequest(
                "New Title", "New description",
                Visibility.PRIVATE, Difficulty.EASY,
                false, false,
                30, 15,
                categoryId,
                List.of()
        );
        String updateJson = objectMapper.writeValueAsString(update);

        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("New Title")))
                .andExpect(jsonPath("$.description", is("New description")))
                .andExpect(jsonPath("$.visibility", is("PRIVATE")))
                .andExpect(jsonPath("$.difficulty", is("EASY")))
                .andExpect(jsonPath("$.isRepetitionEnabled", is(false)))
                .andExpect(jsonPath("$.timerEnabled", is(false)))
                .andExpect(jsonPath("$.estimatedTime", is(30)))
                .andExpect(jsonPath("$.timerDuration", is(15)))
                .andExpect(jsonPath("$.categoryId", is(categoryId.toString())))
                .andExpect(jsonPath("$.tagIds", hasSize(0)));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with subset of fields -> returns 200 OK and leaves others intact")
    void updateQuiz_partialFields_adminReturns200() throws Exception {
        CreateQuizRequest create = new CreateQuizRequest(
                "Initial Title", "Initial desc",
                Visibility.PUBLIC, Difficulty.MEDIUM,
                true, true,
                25, 12,
                categoryId,
                List.of(tagId)
        );
        String createJson = objectMapper.writeValueAsString(create);
        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        String partialJson = """
        {
          "title":"Updated Title",
          "estimatedTime":15,
          "timerDuration":7
        }
        """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(partialJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Updated Title")))
                .andExpect(jsonPath("$.estimatedTime", is(15)))
                .andExpect(jsonPath("$.timerDuration", is(7)))
                .andExpect(jsonPath("$.description", is("Initial desc")))
                .andExpect(jsonPath("$.visibility", is("PUBLIC")))
                .andExpect(jsonPath("$.difficulty", is("MEDIUM")))
                .andExpect(jsonPath("$.isRepetitionEnabled", is(true)))
                .andExpect(jsonPath("$.timerEnabled", is(true)))
                .andExpect(jsonPath("$.categoryId", is(categoryId.toString())))
                .andExpect(jsonPath("$.tagIds[0]", is(tagId.toString())));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with too short title -> returns 400 BAD_REQUEST")
    void updateQuiz_titleTooShort_returns400() throws Exception {
        UUID id = createSampleQuiz();
        String json = """
        { "title":"ab" }
        """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title length must be between 3 and 100 characters"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with too long title -> returns 400 BAD_REQUEST")
    void updateQuiz_titleTooLong_returns400() throws Exception {
        UUID id = createSampleQuiz();
        String longTitle = "x".repeat(101);
        String json = """
        { "title":"%s" }
        """.formatted(longTitle);
        mockMvc.perform(patch("/api/v1/quizzes/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title length must be between 3 and 100 characters"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with too long description -> returns 400 BAD_REQUEST")
    void updateQuiz_descriptionTooLong_returns400() throws Exception {
        UUID id = createSampleQuiz();
        String longDesc = "d".repeat(1001);
        String json = """
        { "description":"%s" }
        """.formatted(longDesc);
        mockMvc.perform(patch("/api/v1/quizzes/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Description must be at most 1000 characters long"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with estimatedTime < 1 -> returns 400 BAD_REQUEST")
    void updateQuiz_estimatedTimeTooLow_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        String json = """
        { "estimatedTime": 0 }
        """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Estimated time must be at least 1 minute"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with estimatedTime > 180 -> returns 400 BAD_REQUEST")
    void updateQuiz_estimatedTimeTooHigh_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        String json = """
        { "estimatedTime": 181 }
        """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Estimated time must be at most 180 minutes"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with timerDuration < 1 -> returns 400 BAD_REQUEST")
    void updateQuiz_timerDurationTooLow_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        String json = """
        { "timerDuration": 0 }
        """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Timer duration must be at least 1 minute"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with timerDuration > 180 -> returns 400 BAD_REQUEST")
    void updateQuiz_timerDurationTooHigh_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        String json = """
        { "timerDuration": 181 }
        """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Timer duration must be at most 180 minutes"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with non-existent ID -> returns 404 NOT_FOUND")
    void updateQuiz_nonexistentId_returns404() throws Exception {
        UUID missing = UUID.randomUUID();
        String json = """
        { "title":"Valid Title" }
        """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Quiz " + missing + " not found"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with unknown tagId -> returns 404 NOT_FOUND")
    void updateQuiz_unknownTagId_returns404() throws Exception {
        UUID quizId = createSampleQuiz();
        UUID badTag = UUID.randomUUID();
        String json = """
        { "tagIds":["%s"] }
        """.formatted(badTag);
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Tag " + badTag + " not found"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with unknown categoryId -> returns 404 NOT_FOUND")
    void updateQuiz_unknownCategoryId_returns404() throws Exception {
        UUID quizId = createSampleQuiz();
        UUID badCat = UUID.randomUUID();
        String json = """
        { "categoryId":"%s" }
        """.formatted(badCat);
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Category " + badCat + " not found"))));
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes/{id} with non-existent ID -> returns 404 NOT_FOUND")
    void deleteQuiz_nonexistentId_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/quizzes/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/questions/{questionId} with non-existent quiz -> returns 404 NOT_FOUND")
    void addQuestion_nonexistentQuiz_returns404() throws Exception {
        UUID badQuiz = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/quizzes/{quizId}/questions/{questionId}", badQuiz, questionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + badQuiz + " not found")));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/questions/{questionId} with non-existent question -> returns 404 NOT_FOUND")
    void addQuestion_nonexistentQuestion_returns404() throws Exception {
        CreateQuizRequest create = new CreateQuizRequest(
                "Temp Quiz", "desc",
                null, null,
                false, false,
                5, 2,
                categoryId,
                List.of(tagId)
        );
        String quizJson = objectMapper.writeValueAsString(create);
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quizJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID validQuiz = UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());

        UUID badQuestion = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/quizzes/{quizId}/questions/{questionId}", validQuiz, badQuestion))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Question " + badQuestion + " not found")));
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes/{quizId}/tags/{tagId} with non-existent quizId returns 404 NOT_FOUND")
    void removeTagFromQuiz_nonexistentQuiz_returns404() throws Exception {
        UUID badQuizId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/quizzes/{quizId}/tags/{tagId}", badQuizId, tagId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + badQuizId + " not found")));
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes/{quizId}/tags/{tagId} with non-existent tagId returns 204 NO_CONTENT")
    void removeTagFromQuiz_nonexistentTag_returns204() throws Exception {
        CreateQuizRequest create = new CreateQuizRequest(
                "QuizForRemove", "desc",
                null, null,
                false, false,
                10, 5,
                categoryId,
                List.of(tagId)
        );
        String quizJson = objectMapper.writeValueAsString(create);
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quizJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());

        UUID badTagId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/quizzes/{quizId}/tags/{tagId}", quizId, badTagId))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{quizId}/category/{categoryId} with non-existent quizId -> returns 404 NOT_FOUND")
    void changeCategory_nonexistentQuiz_returns404() throws Exception {
        UUID badQuizId = UUID.randomUUID();
        mockMvc.perform(patch("/api/v1/quizzes/{quizId}/category/{categoryId}", badQuizId, categoryId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + badQuizId + " not found")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{quizId}/category/{categoryId} with non-existent categoryId returns 404 NOT_FOUND")
    void changeCategory_nonexistentCategory_returns404() throws Exception {
        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQuizRequest("Quiz2", null, null, null, false, false, 5, 2, categoryId, List.of())
                        )))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        UUID badCategoryId = UUID.randomUUID();
        mockMvc.perform(patch("/api/v1/quizzes/{quizId}/category/{categoryId}", quizId, badCategoryId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Category " + badCategoryId + " not found")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/results with completed attempts returns 200 OK with summary")
    void getQuizResults_withCompletedAttempts_returns200() throws Exception {
        String quizBody = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQuizRequest("ResultsQuiz","desc", null, null, false, false, 5, 2, categoryId, List.of())
                        )))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(quizBody).get("quizId").asText());

        JsonNode content = objectMapper.readTree("{\"answer\":true}");
        String qBody = mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQuestionRequest(QuestionType.TRUE_FALSE,
                                        Difficulty.EASY,
                                        "Question?",
                                        content,
                                        null,
                                        null,
                                        null,
                                        List.of(quizId),
                                        List.of()
                                )
                        )))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID questionId = UUID.fromString(objectMapper.readTree(qBody).get("questionId").asText());

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/questions/{ques}", quizId, questionId))
                .andExpect(status().isNoContent());

        String atBody = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(atBody).get("attemptId").asText());

        AnswerSubmissionRequest sub = new AnswerSubmissionRequest(questionId, content);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sub)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/quizzes/{quizId}/results", quizId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizId", is(quizId.toString())))
                .andExpect(jsonPath("$.attemptsCount", is(1)))
                .andExpect(jsonPath("$.averageScore", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.bestScore", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.worstScore", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.passRate", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.questionStats", hasSize(1)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/results with non-existent quizId returns 404 NOT_FOUND")
    void getQuizResults_nonexistentQuiz_returns404() throws Exception {
        UUID badQuizId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/quizzes/{quizId}/results", badQuizId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + badQuizId + " not found")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/results when no attempts → returns 200 OK with zeroed summary")
    void getQuizResults_Empty() throws Exception {
        // --- create a quiz first (reuse your existing setup) ---
        CreateQuizRequest create = new CreateQuizRequest(
                "Results Quiz", "desc",
                null, null,
                false, false,
                10, 5,
                categoryId,
                List.of(tagId)
        );
        String createJson = objectMapper.writeValueAsString(create);
        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        // --- now call the results endpoint ---
        mockMvc.perform(get("/api/v1/quizzes/{quizId}/results", quizId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizId", is(quizId.toString())))
                .andExpect(jsonPath("$.attemptsCount", is(0)))
                .andExpect(jsonPath("$.averageScore", is(0.0)))
                .andExpect(jsonPath("$.bestScore", is(0.0)))
                .andExpect(jsonPath("$.worstScore", is(0.0)))
                .andExpect(jsonPath("$.passRate", is(0.0)))
                .andExpect(jsonPath("$.questionStats", hasSize(0)));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/visibility → PUBLIC ▸ returns 200 & updates field")
    void changeVisibility_validPayload_adminReturns200() throws Exception {
        UUID quizId = createSampleQuiz();

        String body = """
        { "isPublic": true }
        """;

        mockMvc.perform(patch("/api/v1/quizzes/{id}/visibility", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(quizId.toString())))
                .andExpect(jsonPath("$.visibility", is("PUBLIC")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/visibility with non-existent ID → 404 NOT_FOUND")
    void changeVisibility_nonexistentQuiz_returns404() throws Exception {
        UUID missing = UUID.randomUUID();
        String body = """
        { "isPublic": false }
        """;

        mockMvc.perform(patch("/api/v1/quizzes/{id}/visibility", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + missing + " not found")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/visibility without ADMIN role → 403 FORBIDDEN")
    void changeVisibility_withoutAdminRole_returns403() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(patch("/api/v1/quizzes/{id}/visibility", quizId)
                        .with(user("user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"isPublic\": true }"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/visibility with malformed body → 400 BAD_REQUEST")
    void changeVisibility_invalidBody_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(patch("/api/v1/quizzes/{id}/visibility", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details",
                        hasItem(containsString("isPublic: must not be null"))));
    }


    private UUID createSampleQuiz() throws Exception {
        CreateQuizRequest req = new CreateQuizRequest(
                "Sample", null,
                null, null,
                false, false,
                5, 2,
                categoryId,
                List.of()
        );
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());
    }


}