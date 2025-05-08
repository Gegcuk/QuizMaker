package uk.gegc.quizmaker.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.dto.quiz.UpdateQuizRequest;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create"
})
class QuizControllerIntegrationTest {


    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Autowired UserRepository      userRepository;
    @Autowired CategoryRepository  categoryRepository;
    @Autowired TagRepository       tagRepository;
    @Autowired QuestionRepository  questionRepository;
    @Autowired QuizRepository      quizRepository;

    private static final UUID DEFAULT_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    private UUID categoryId;
    private UUID tagId;
    private UUID questionId;

    @BeforeEach
    void setUp() {
        // 1) Clear everything
        quizRepository.deleteAll();
        questionRepository.deleteAll();
        tagRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        // 2) Seed the default user via plain JDBC
        jdbc.update("""
            INSERT INTO users(
                user_id, username, email, password,
                created_at, is_active, is_deleted
            ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
            """,
                DEFAULT_USER_ID,
                "defaultUser",
                "def@ex.com",
                "pw",
                true,
                false
        );

        // 3) Now use JPA for the rest…
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
        q.setAttachmentUrl(null);
        questionRepository.save(q);
        questionId = q.getId();
    }


    @Test
    void fullQuizCrudAndAssociations() throws Exception {
        // --- 1) Create a quiz ---
        CreateQuizRequest create = new CreateQuizRequest(
                "My Quiz", "desc",
                null, // defaults
                null,
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
                        .param("page","0").param("size","20"))
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
                null,null,null,
                null,null,
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

        mockMvc.perform(get("/api/v1/quizzes/{id}", quizId))
                .andExpect(jsonPath("$.createdAt").exists()); // we only check existence

        mockMvc.perform(delete("/api/v1/quizzes/{q}/questions/{ques}", quizId, questionId))
                .andExpect(status().isNoContent());

        // --- 6) Add and remove tag ---
        mockMvc.perform(post("/api/v1/quizzes/{q}/tags/{t}", quizId, tagId))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/quizzes/{q}/tags/{t}", quizId, tagId))
                .andExpect(status().isNoContent());

        // --- 7) Change category ---
        // create new category
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
}