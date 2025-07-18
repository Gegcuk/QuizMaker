package uk.gegc.quizmaker.controller;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.ResultActions;
import uk.gegc.quizmaker.dto.attempt.AnswerSubmissionRequest;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.attempt.AttemptMode;
import uk.gegc.quizmaker.model.attempt.AttemptStatus;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.quiz.Visibility;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.attempt.AttemptRepository;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.params.provider.Arguments.of;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gegc.quizmaker.model.question.QuestionType.TRUE_FALSE;


@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create"
})

@DisplayName("Integration Tests AttemptController")
@WithMockUser(username = "defaultUser", roles = "ADMIN")
public class AttemptControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    QuizRepository quizRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JdbcTemplate jdbc;

    private UUID quizId;
    @Autowired
    private AttemptRepository attemptRepository;

    /**
     * Parameterized happy‐paths for every QuestionType
     */
    static Stream<Arguments> questionScenarios() {
        return Stream.of(
                of(
                        TRUE_FALSE,
                        "{\"answer\":true}",
                        "{\"answer\":true}",
                        true
                ),
                of(
                        QuestionType.MCQ_SINGLE,
                        """
                                {"options":[
                                  {"id":"a","text":"Option A","correct":true},
                                  {"id":"b","text":"Option B","correct":false}
                                ]}
                                """,
                        "{\"selectedOptionId\":\"a\"}",
                        true
                ),
                of(
                        QuestionType.MCQ_MULTI,
                        """
                                {"options":[
                                  {"id":"a","text":"A","correct":true},
                                  {"id":"b","text":"B","correct":false},
                                  {"id":"c","text":"C","correct":true}
                                ]}
                                """,
                        "{\"selectedOptionIds\":[\"a\",\"c\"]}",
                        true
                ),
                of(
                        QuestionType.FILL_GAP,
                        """
                                {"text":"Fill _ here","gaps":[{"id":1,"answer":"foo"}]}
                                """,
                        "{\"gaps\":[{\"id\":1,\"answer\":\"foo\"}]}",
                        true
                ),
                of(
                        QuestionType.ORDERING,
                        """
                                {"items":[
                                  {"id":1,"text":"one"},
                                  {"id":2,"text":"two"},
                                  {"id":3,"text":"three"}
                                ]}
                                """,
                        "{\"itemIds\":[1,2,3]}",
                        true
                ),
                of(
                        QuestionType.COMPLIANCE,
                        """
                                {"statements":[
                                  {"id":1,"text":"s1","compliant":true},
                                  {"id":2,"text":"s2","compliant":false}
                                ]}
                                """,
                        "{\"selectedStatementIds\":[1]}",
                        true
                ),
                of(
                        QuestionType.HOTSPOT,
                        """
                                {"imageUrl":"http://img","regions":[
                                  {"id":1,"x":0,"y":0,"width":10,"height":10,"correct":true},
                                  {"id":2,"x":5,"y":5,"width":5,"height":5,"correct":false}
                                ]}
                                """,
                        "{\"regionId\":1}",
                        true
                ),
                of(
                        QuestionType.OPEN,
                        "{\"answer\":\"hello\"}",
                        "{\"answer\":\"hello\"}",
                        true
                )
        );
    }

    @BeforeEach
    void setUp() throws Exception {
        quizRepository.deleteAll();
        categoryRepository.deleteAll();

        User defaultUser = new User();
        defaultUser.setUsername("defaultUser");
        defaultUser.setEmail("def@ex.com");
        defaultUser.setHashedPassword("pw");
        defaultUser.setActive(true);
        defaultUser.setDeleted(false);
        userRepository.save(defaultUser);

        Category cat = new Category();
        cat.setName("General");
        categoryRepository.save(cat);

        CreateQuizRequest cq = new CreateQuizRequest(
                "Integration Quiz", "desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false, 10, 5,
                cat.getId(), List.of()
        );
        String body = objectMapper.writeValueAsString(cq);
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        quizId = UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());
    }

    @DisplayName("[HAPPY] Parameterized: submit + complete for each QuestionType")
    @ParameterizedTest(name = "[{index}] {0} → correct?={3}")
    @MethodSource("questionScenarios")
    void happyPathSubmitAnswers(
            QuestionType type,
            String contentJson,
            String responseJson,
            boolean expectedCorrect
    ) throws Exception {
        CreateQuestionRequest qr = new CreateQuestionRequest();
        qr.setType(type);
        qr.setDifficulty(Difficulty.EASY);
        qr.setQuestionText("Test " + type);
        qr.setContent(objectMapper.readTree(contentJson));
        qr.setQuizIds(List.of(quizId));
        qr.setTagIds(List.of());
        String qreqJson = objectMapper.writeValueAsString(qr);

        String qresp = mockMvc.perform(post("/api/v1/questions")
                        .contentType(APPLICATION_JSON)
                        .content(qreqJson))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID questionId = UUID.fromString(objectMapper.readTree(qresp).get("questionId").asText());

        String startResp = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(startResp).get("attemptId").asText());

        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree(responseJson)
        );
        String sJson = objectMapper.writeValueAsString(submission);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .contentType(APPLICATION_JSON)
                        .content(sJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId", is(questionId.toString())))
                .andExpect(jsonPath("$.isCorrect", is(expectedCorrect)));

        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctCount", is(expectedCorrect ? 1 : 0)));
    }

    @Test
    @DisplayName("SubmitAnswer with invalid attemptId → returns 404 NOT_FOUND")
    void submitWithBadAttemptId() throws Exception {
        UUID fakeId = UUID.randomUUID();
        var req = new AnswerSubmissionRequest(fakeId, objectMapper.createObjectNode());
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", fakeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/quizzes/{quizId} without authentication → returns 403 FORBIDDEN")
    void startAttempt_anonymous_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SubmitAnswer with missing response → returns 400 BAD_REQUEST")
    void submitValidationError() throws Exception {
        CreateQuestionRequest createQuestionRequest = new CreateQuestionRequest();
        createQuestionRequest.setType(TRUE_FALSE);
        createQuestionRequest.setDifficulty(Difficulty.MEDIUM);
        createQuestionRequest.setQuestionText("Test");
        createQuestionRequest.setContent(objectMapper.readTree("{\"answer\":true}"));
        createQuestionRequest.setQuizIds(List.of(quizId));
        createQuestionRequest.setTagIds(List.of());
        String questionRequestJson = objectMapper.writeValueAsString(createQuestionRequest);
        String questionResponse = mockMvc.perform(post("/api/v1/questions")
                        .contentType(APPLICATION_JSON)
                        .content(questionRequestJson))
                .andReturn().getResponse().getContentAsString();
        UUID questionId = UUID.fromString(objectMapper.readTree(questionResponse).get("questionId").asText());

        String start = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)).andReturn().getResponse().getContentAsString();

        UUID attemptId = UUID.fromString(objectMapper.readTree(start).get("attemptId").asText());

        String badJson = "{\"questionId\":\"" + questionId + "\"}";

        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .contentType(APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/attempts without authentication → returns 401 UNAUTHORIZED")
    void listAttempts_anonymousReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/attempts")
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/attempts without authentication → returns 401 UNAUTHORIZED")
    void listAttempts_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/attempts")
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id} as non-owner → returns 403 FORBIDDEN")
    void getAttempt_nonOwner_returns403() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(resp).get("attemptId").asText());

        mockMvc.perform(get("/api/v1/attempts/{id}", attemptId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers without authentication → returns 401 UNAUTHORIZED")
    void submitAnswer_anonymous_returns401() throws Exception {
        UUID attemptId = UUID.randomUUID();
        String payload = """
                {
                  "questionId":"%s",
                  "response":{}
                }
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers as non-owner → returns 403 FORBIDDEN")
    void submitAnswer_nonOwner_returns403() throws Exception {
        var defaultUser = userRepository.findByUsername("defaultUser").get();
        var quiz = quizRepository.findById(quizId).orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        Attempt attempt = new Attempt();
        attempt.setUser(defaultUser);
        attempt.setQuiz(quiz);
        attempt.setMode(AttemptMode.ALL_AT_ONCE);
        attempt.setStatus(AttemptStatus.IN_PROGRESS);
        attemptRepository.save(attempt);

        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}");
        String payload = """
                {
                  "questionId":"%s",
                  "response":{"answer":true}
                }
                """.formatted(questionId);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attempt.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers after attempt completed → returns 409 CONFLICT")
    void submitAnswer_afterComplete_returns409() throws Exception {
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}");
        UUID attemptId = startAttempt();

        postAnswer(attemptId, questionId, "{\"answer\":true}")
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId))
                .andExpect(status().isOk());

        postAnswer(attemptId, questionId, "{\"answer\":true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.details[0]", containsString("Cannot submit answer to attempt with status COMPLETED")));
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers/batch without authentication → returns 403 FORBIDDEN")
    void submitBatch_anonymous_returns403() throws Exception {
        UUID attemptId = startAttempt();
        String batchJson = "{\"answers\":[{\"questionId\":\"00000000-0000-0000-0000-000000000000\",\"response\":{}}]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .with(anonymous())
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers/batch by non-owner → returns 403 FORBIDDEN")
    void submitBatch_nonOwner_returns403() throws Exception {
        User other = new User();
        other.setUsername("otherUser");
        other.setEmail("other@ex.com");
        other.setHashedPassword("pw");
        other.setActive(true);
        other.setDeleted(false);
        userRepository.save(other);

        UUID attemptId = startAttempt();
        String batchJson = "{\"answers\":[{\"questionId\":\"00000000-0000-0000-0000-000000000000\",\"response\":{}}]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .with(user("otherUser").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers/batch with invalid attemptId → returns 404 NOT_FOUND")
    void submitBatch_invalidAttemptId_returns404() throws Exception {
        UUID fakeId = UUID.randomUUID();
        String batchJson = "{\"answers\":[{\"questionId\":\"00000000-0000-0000-0000-000000000000\",\"response\":{}}]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", fakeId)
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/complete without authentication → returns 403 FORBIDDEN")
    void completeAttempt_anonymous_returns403() throws Exception {
        UUID attemptId = startAttempt();
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }


    @Test
    @DisplayName("POST /api/v1/attempts/{id}/complete by non-owner → returns 403 FORBIDDEN")
    void completeAttempt_nonOwner_returns403() throws Exception {
        User other = new User();
        other.setUsername("otherUser");
        other.setEmail("other@ex.com");
        other.setHashedPassword("password");
        other.setActive(true);
        other.setDeleted(false);
        userRepository.save(other);

        UUID attemptId = startAttempt();
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)
                        .with(user("otherUser").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/complete with nonexistent ID → returns 404 NOT_FOUND")
    void completeAttempt_invalidAttemptId_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Batch submission in ONE_BY_ONE mode → returns 409 CONFLICT")
    void batchInOneByOneMode() throws Exception {
        String startJson = "{\"mode\":\"ONE_BY_ONE\"}";
        String start = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .contentType(APPLICATION_JSON)
                        .content(startJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID attemptId = UUID.fromString(objectMapper.readTree(start).get("attemptId").asText());

        String batchJson = """
                {
                  "answers": [
                    {
                      "questionId": "00000000-0000-0000-0000-000000000000",
                      "response": {}
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.details[0]", containsString("Batch submissions only allowed")));
    }

    @Test
    @DisplayName("Completing an already completed attempt → returns 409 CONFLICT")
    void completeTwice() throws Exception {

        String start = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)).andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(start).get("attemptId").asText());

        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/v1/attempts → returns paginated and sorted attempts")
    void paginationAndSorting() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/attempts")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "startedAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    @DisplayName("GET /api/v1/attempts?quizId={quizId}&userId={userId} → returns filtered attempts by quizId and userId")
    void filteringByQuizIdAndUserId() throws Exception {

        mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId));
        mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId));

        mockMvc.perform(get("/api/v1/attempts")
                        .param("quizId", quizId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)));

        mockMvc.perform(get("/api/v1/attempts")
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    @DisplayName("Timed mode attempt timeout → returns 409 CONFLICT")
    void timedModeTimeout() throws Exception {
        CreateQuizRequest timedQuiz = new CreateQuizRequest(
                "Timed",
                "description",
                Visibility.PRIVATE,
                Difficulty.EASY,
                false,
                true,
                1,
                1,
                categoryRepository.findAll().get(0).getId(),
                List.of()
        );

        String timedJson = objectMapper.writeValueAsString(timedQuiz);
        String timedResponse = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(APPLICATION_JSON)
                        .content(timedJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID timedQuizId = UUID.fromString(objectMapper.readTree(timedResponse).get("quizId").asText());

        JsonNode dummyContent = objectMapper.readTree("{\"options\":[{\"id\":\"A\",\"text\":\"foo\",\"correct\":true},{\"id\":\"B\",\"text\":\"bar\",\"correct\":false}]}");
        CreateQuestionRequest createQuestionRequest = new CreateQuestionRequest(QuestionType.MCQ_SINGLE,
                Difficulty.EASY,
                "What's foo?",
                dummyContent,
                null,
                null,
                null,
                List.of(),
                List.of());
        String questionJson = objectMapper.writeValueAsString(createQuestionRequest);
        String questionResponse = mockMvc.perform(post("/api/v1/questions")
                        .contentType(APPLICATION_JSON)
                        .content(questionJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID questionId = UUID.fromString(objectMapper.readTree(questionResponse).get("questionId").asText());

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/questions/{questionId}", timedQuizId, questionId))
                .andExpect(status().isNoContent());

        String startJson = "{\"mode\":\"TIMED\"}";
        String attemptResponse = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", timedQuizId)
                        .contentType(APPLICATION_JSON)
                        .content(startJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID attemptId = UUID.fromString(objectMapper.readTree(attemptResponse).get("attemptId").asText());

        Instant expired = Instant.now().minus(2, ChronoUnit.MINUTES);
        jdbc.update(
                "UPDATE attempts SET started_at = ? WHERE id = ?",
                Timestamp.from(expired),
                attemptId
        );

        String badSubmit = String.format(
                "{\"questionId\":\"%s\",\"response\":{}}", questionId
        );

        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .contentType(APPLICATION_JSON)
                        .content(badSubmit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.details[0]", is("Attempt has timed out")));
    }

    @Test
    @DisplayName("Batch submission in ALL_AT_ONCE mode (happy + sad) → succeeds for valid and fails for invalid as expected")
    void batchSubmissionHappyAndSad() throws Exception {

        UUID question1 = createDummyQuestion(TRUE_FALSE, "{ \"answer\": true }");
        UUID question2 = createDummyQuestion(TRUE_FALSE, "{ \"answer\": false }");

        String startJson = "{\"mode\":\"ALL_AT_ONCE\"}";
        String startRequest = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .contentType(APPLICATION_JSON)
                        .content(startJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(startRequest).get("attemptId").asText());

        var batchRequest = objectMapper.createObjectNode();
        var arr = batchRequest.putArray("answers");
        arr.add(objectMapper.createObjectNode()
                .put("questionId", question1.toString())
                .set("response", objectMapper.readTree("{ \"answer\": true }")));
        arr.add(objectMapper.createObjectNode()
                .put("questionId", question2.toString())
                .set("response", objectMapper.readTree("{ \"answer\": false }")));
        String batchJson = objectMapper.writeValueAsString(batchRequest);

        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].isCorrect", is(true)))
                .andExpect(jsonPath("$[1].isCorrect", is(true)));

        String badBatch = "{\"answers\":[{\"questionId\":\"" + question1 + "\"}]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .contentType(APPLICATION_JSON)
                        .content(badBatch))
                .andExpect(status().isBadRequest());


    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id} when ID not found → returns 404 NOT_FOUND")
    void getAttemptNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/attempts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id} → returns attempt details")
    void getAttemptDetails() throws Exception {
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{ \"answer\": true }");
        UUID attemptId = startAttempt();

        postAnswer(attemptId, questionId, "{ \"answer\": true }").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/attempts/{id}", attemptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId", is(attemptId.toString())))
                .andExpect(jsonPath("$.answers", hasSize(1)))
                .andExpect(jsonPath("$.answers[0].isCorrect", is(true)));
    }

    @Test
    @DisplayName("Start attempt for non-existent quiz → returns 404 NOT_FOUND")
    void startAttemptBadQuiz() throws Exception {
        mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/attempts with negative paging params → returns 400 BAD_REQUEST")
    void badPagingParams() throws Exception {
        mockMvc.perform(get("/api/v1/attempts")
                        .param("page", "-1")
                        .param("size", "-5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SubmitAnswer for question not in quiz → returns 404 NOT_FOUND")
    void submitWrongQuestion() throws Exception {
        UUID otherQuiz = createAnotherQuiz();
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{ \"answer\": true }", otherQuiz);
        UUID attemptId = startAttempt();
        postAnswer(attemptId, questionId, "{ \"answer\": true }")
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Submitting duplicate answer to same question → allowed")
    void duplicateAnswerBehavior() throws Exception {
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{ \"answer\": true }");
        UUID attemptId = startAttempt();
        postAnswer(attemptId, questionId, "{ \"answer\": true }").andExpect(status().isOk());
        postAnswer(attemptId, questionId, "{ \"answer\": true }").andExpect(status().isConflict());
    }

    @DisplayName("Batch submission with empty list → returns 400 BAD_REQUEST")
    void batchEmptyList() throws Exception {
        UUID attemptId = startAttempt();
        String bad = "{\"answers\":[]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .contentType(APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("startAttempt returns first question")
    void startAttempt_returnsFirstQuestion() throws Exception {
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}");

        mockMvc.perform(post("/api/v1/attempts/quizzes/{id}", quizId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptId").exists())
                .andExpect(jsonPath("$.firstQuestion.id", is(questionId.toString())));
    }

    @Test
    @DisplayName("startAttempt quiz has no questions returns null firstQuestion")
    void startAttempt_quizHasNoQuestions_returnsNullFirstQuestion() throws Exception {
        mockMvc.perform(post("/api/v1/attempts/quizzes/{id}", quizId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptId").exists())
                .andExpect(jsonPath("$.firstQuestion").value(nullValue()));
    }

    private ResultActions postAnswer(UUID attempt, UUID question, String responseJson) throws Exception {
        var request = new AnswerSubmissionRequest(question, objectMapper.readTree(responseJson));
        return mockMvc.perform(post("/api/v1/attempts/{id}/answers", attempt)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private UUID startAttempt() throws Exception {

        String startRequest = mockMvc.perform(post("/api/v1/attempts/quizzes/{id}", quizId))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(startRequest).get("attemptId").asText());

    }

    private UUID createAnotherQuiz() throws Exception {
        UUID categoryId = categoryRepository.findAll().get(0).getId();

        CreateQuizRequest req = new CreateQuizRequest(
                "Another Quiz",
                "description",
                Visibility.PRIVATE,
                Difficulty.MEDIUM,
                false,
                false,
                10,
                5,
                categoryId,
                List.of()
        );

        String body = objectMapper.writeValueAsString(req);
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());
    }

    private UUID createDummyQuestion(QuestionType type, String contentJson) throws Exception {

        CreateQuestionRequest createQuestionRequest = new CreateQuestionRequest();
        createQuestionRequest.setType(type);
        createQuestionRequest.setDifficulty(Difficulty.EASY);
        createQuestionRequest.setQuestionText("Question?");
        createQuestionRequest.setContent(objectMapper.readTree(contentJson));
        createQuestionRequest.setTagIds(List.of());
        createQuestionRequest.setQuizIds(List.of(quizId));
        String createQuestionRequestString = objectMapper.writeValueAsString(createQuestionRequest);

        String response = mockMvc.perform(post("/api/v1/questions")
                        .contentType(APPLICATION_JSON)
                        .content(createQuestionRequestString))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID questionId = UUID.fromString(objectMapper.readTree(response).get("questionId").asText());
        return questionId;
    }

    private UUID createDummyQuestion(QuestionType type, String contentJson, UUID targetQuizId) throws Exception {
        CreateQuestionRequest qr = new CreateQuestionRequest();
        qr.setType(type);
        qr.setDifficulty(Difficulty.EASY);
        qr.setQuestionText("Q for " + type);
        qr.setContent(objectMapper.readTree(contentJson));
        qr.setQuizIds(List.of(targetQuizId));
        qr.setTagIds(List.of());

        String qbody = objectMapper.writeValueAsString(qr);
        String qresp = mockMvc.perform(post("/api/v1/questions")
                        .contentType(APPLICATION_JSON)
                        .content(qbody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(qresp).get("questionId").asText());
    }


}