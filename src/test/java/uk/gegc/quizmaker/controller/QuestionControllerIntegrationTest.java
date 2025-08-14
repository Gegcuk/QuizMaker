package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = AFTER_CLASS)
@Transactional
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("Integration Tests QuestionController")
public class QuestionControllerIntegrationTest {

    @Autowired
    QuestionRepository questionRepository;
    @Autowired
    QuizRepository quizRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String payload = """
            {
              "type":"TRUE_FALSE",
              "difficulty":"EASY",
              "questionText":"Is this correct?",
              "content":{"answer":true}
            }
            """;

    static Stream<Arguments> happyPathPayloads() {
        return Stream.of(
                Arguments.of("MCQ_SINGLE (2 opts, 1 correct)", """
                        {
                          "type":"MCQ_SINGLE","difficulty":"EASY","questionText":"Pick one",
                          "content":{"options":[
                              {"id":"a","text":"A","correct":false},
                              {"id":"b","text":"B","correct":true}
                            ]}
                        }
                        """),
                Arguments.of("MCQ_MULTI (3 opts, >=1 correct)", """
                        {
                          "type":"MCQ_MULTI","difficulty":"MEDIUM","questionText":"Pick many",
                          "content":{"options":[
                              {"id":"a","text":"A","correct":true},
                              {"id":"b","text":"B","correct":false},
                              {"id":"c","text":"C","correct":true}
                            ]}
                        }
                        """),
                Arguments.of("TRUE_FALSE (true)", """
                        {
                          "type":"TRUE_FALSE","difficulty":"EASY","questionText":"T or F?",
                          "content":{"answer":true}
                        }
                        """),
                Arguments.of("TRUE_FALSE (false)", """
                        {
                          "type":"TRUE_FALSE","difficulty":"EASY","questionText":"T or F?",
                          "content":{"answer":false}
                        }
                        """),
                Arguments.of("OPEN", """
                        {
                          "type":"OPEN","difficulty":"HARD","questionText":"Explain?",
                          "content":{"answer":"Because..."}
                        }
                        """),
                Arguments.of("FILL_GAP", """
                        {
                          "type":"FILL_GAP","difficulty":"MEDIUM","questionText":"Fill:",
                          "content":{
                            "text":"___ is Java","gaps":[
                              {"id":1,"answer":"Java"}
                            ]
                          }
                        }
                        """),
                Arguments.of("ORDERING", """
                        {
                          "type":"ORDERING","difficulty":"HARD","questionText":"Order these",
                          "content":{"items":[
                            {"id":1,"text":"First"},
                            {"id":2,"text":"Second"}
                          ]}
                        }
                        """),
                Arguments.of("HOTSPOT", """
                        {
                          "type":"HOTSPOT","difficulty":"MEDIUM","questionText":"Click",
                          "content":{
                            "imageUrl":"http://img.png",
                            "regions":[
                              {"id":1,"x":10,"y":20,"width":30,"height":40,"correct":true},
                              {"id":2,"x":50,"y":60,"width":70,"height":80,"correct":false}
                            ]
                          }
                        }
                        """),
                Arguments.of("COMPLIANCE", """
                        {
                          "type":"COMPLIANCE","difficulty":"MEDIUM","questionText":"Agree?",
                          "content":{
                            "statements":[
                              {"id":1,"text":"Yes","compliant":true},
                              {"id":2,"text":"No","compliant":false}
                            ]
                          }
                        }
                        """)
        );
    }

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                // missing required
                Arguments.of("Missing type", """
                        {"difficulty":"EASY","questionText":"Q?","content":{"answer":true}}
                        """),
                Arguments.of("Missing difficulty", """
                        {"type":"TRUE_FALSE","questionText":"Q?","content":{"answer":true}}
                        """),
                Arguments.of("Missing questionText", """
                        {"type":"TRUE_FALSE","difficulty":"EASY","content":{"answer":true}}
                        """),
                // text too short / too long
                Arguments.of("questionText too short", """
                        {"type":"TRUE_FALSE","difficulty":"EASY","questionText":"Hi","content":{"answer":true}}
                        """),
                Arguments.of("questionText too long", """
                        {
                          "type":"TRUE_FALSE","difficulty":"EASY",
                          "questionText":"%s","content":{"answer":true}
                        }
                        """.formatted("A".repeat(1001))),
                // content null
                Arguments.of("content null", """
                        {"type":"TRUE_FALSE","difficulty":"EASY","questionText":"Q?","content":null}
                        """),
                // MCQ wrong shape
                Arguments.of("MCQ_SINGLE missing options", """
                        {"type":"MCQ_SINGLE","difficulty":"EASY","questionText":"Q?","content":{}}
                        """),
                Arguments.of("MCQ_SINGLE wrong correct-count", """
                        {
                          "type":"MCQ_SINGLE","difficulty":"EASY","questionText":"Q?",
                          "content":{"options":[
                            {"id":"a","text":"A","correct":false},
                            {"id":"b","text":"B","correct":false}
                          ]}
                        }
                        """),
                // TRUE_FALSE wrong type
                Arguments.of("TRUE_FALSE non-boolean answer", """
                        {"type":"TRUE_FALSE","difficulty":"EASY","questionText":"Q?","content":{"answer":"yes"}}
                        """),
                // ORDERING missing items
                Arguments.of("ORDERING missing items", """
                        {"type":"ORDERING","difficulty":"HARD","questionText":"Q?","content":{}}
                        """),
                // HOTSPOT missing imageUrl
                Arguments.of("HOTSPOT missing imageUrl", """
                        {
                          "type":"HOTSPOT","difficulty":"MEDIUM","questionText":"Q?",
                          "content":{"regions":[{"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true}]}
                        }
                        """),
                // COMPLIANCE no compliant true
                Arguments.of("COMPLIANCE no compliant", """
                        {
                          "type":"COMPLIANCE","difficulty":"MEDIUM","questionText":"Q?",
                          "content":{"statements":[{"id":1,"text":"X","compliant":false}]}
                        }
                        """),
                // malformed JSON
                Arguments.of("Malformed JSON", """
                        {"type":"TRUE_FALSE","difficulty":"EASY","questionText":"Q?","content":{"answer":true
                        """)
        );
    }

    @ParameterizedTest(name = "{0}")
    @WithMockUser(roles = "ADMIN")
    @MethodSource("happyPathPayloads")
    void createQuestion_HappyPath_thanReturns201(String name, String jsonPayload) throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questionId").exists());
    }

    @Test
    @DisplayName("POST /api/v1/questions without authentication returns 403 FORBIDDEN")
    void createQuestion_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /api/v1/questions with USER role returns 403 FORBIDDEN")
    void createQuestion_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions missing type returns 400 BAD_REQUEST")
    void createQuestion_missingType_returns400() throws Exception {
        String missingType = """
                {
                  "difficulty":"EASY",
                  "questionText":"Is this correct?",
                  "content":{"answer":true}
                }
                """;

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingType))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Type must not be null"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions missing difficulty returns 400 BAD_REQUEST")
    void createQuestion_missingDifficulty_returns400() throws Exception {
        String missingDifficulty = """
                {
                  "type":"TRUE_FALSE",
                  "questionText":"Is this correct?",
                  "content":{"answer":true}
                }
                """;

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingDifficulty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Difficulty must not be null"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions missing questionText returns 400 BAD_REQUEST")
    void createQuestion_missingQuestionText_returns400() throws Exception {
        String missingText = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "content":{"answer":true}
                }
                """;

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingText))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Question text must not be blank"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions questionText too short returns 400 BAD_REQUEST")
    void createQuestion_questionTextTooShort_returns400() throws Exception {
        String tooShort = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Hi",
                  "content":{"answer":true}
                }
                """;

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooShort))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Question text length must be between 3 and 1000 characters"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions questionText too long returns 400 BAD_REQUEST")
    void createQuestion_questionTextTooLong_returns400() throws Exception {
        String longText = "A".repeat(1001);
        String tooLong = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"%s",
                  "content":{"answer":true}
                }
                """.formatted(longText);

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Question text length must be between 3 and 1000 characters"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions content null returns 400 BAD_REQUEST")
    void createQuestion_contentNull_returns400() throws Exception {
        String nullContent = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Is this correct?",
                  "content":null
                }
                """;

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullContent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Invalid JSON for TRUE_FALSE question"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions malformed JSON returns 400 BAD_REQUEST with parse error")
    void createQuestion_malformedJson_returns400() throws Exception {
        // missing closing brace
        String badJson = """
                    {
                      "type":"TRUE_FALSE",
                      "difficulty":"EASY",
                      "questionText":"Is this correct?",
                      "content":{"answer":true}
                """;

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Malformed JSON")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions hint too long returns 400 BAD_REQUEST")
    void createQuestion_hintTooLong_returns400() throws Exception {
        String longHint = "a".repeat(501);
        String json = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Is this correct?",
                  "content":{"answer":true},
                  "hint":"%s"
                }
                """.formatted(longHint);

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Hint length must be less than 500 characters"
                ))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions explanation too long returns 400 BAD_REQUEST")
    void createQuestion_explanationTooLong_returns400() throws Exception {
        String longExplanation = "e".repeat(2001);
        String json = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Is this correct?",
                  "content":{"answer":true},
                  "explanation":"%s"
                }
                """.formatted(longExplanation);

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Explanation must be less than 2000 characters"
                ))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions attachmentUrl too long returns 400 BAD_REQUEST")
    void createQuestion_attachmentUrlTooLong_returns400() throws Exception {
        String longUrl = "http://" + "a".repeat(2050) + ".com";
        String json = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Is this correct?",
                  "content":{"answer":true},
                  "attachmentUrl":"%s"
                }
                """.formatted(longUrl);

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "URL length is limited by 2048 characters"
                ))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions unknown quizId returns 404 NOT_FOUND")
    void createQuestion_unknownQuizId_returns404() throws Exception {
        String badQuizId = UUID.randomUUID().toString();
        String json = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Is this correct?",
                  "content":{"answer":true},
                  "quizIds":["%s"]
                }
                """.formatted(badQuizId);

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Quiz " + badQuizId + " not found"
                ))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/questions unknown tagId returns 404 NOT_FOUND")
    void createQuestion_unknownTagId_returns404() throws Exception {
        String badTagId = UUID.randomUUID().toString();
        String json = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Is this correct?",
                  "content":{"answer":true},
                  "tagIds":["%s"]
                }
                """.formatted(badTagId);

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Tag " + badTagId + " not found"
                ))));
    }

    @ParameterizedTest(name = "{0}")
    @WithMockUser(roles = "ADMIN")
    @MethodSource("invalidPayloads")
    void createQuestion_invalidPayloadPath_thanReturns400(String name, String jsonPayload) throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/questions → empty DB returns totalElements=0")
    void listQuestions_emptyDb_returnsEmptyPage() throws Exception {
        questionRepository.deleteAll();

        mockMvc.perform(get("/api/v1/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/questions → with data returns all questions")
    void listQuestions_withData_returnsAll() throws Exception {
        questionRepository.deleteAll();

        Question q1 = new Question();
        q1.setType(QuestionType.TRUE_FALSE);
        q1.setDifficulty(Difficulty.EASY);
        q1.setQuestionText("Q1");
        q1.setContent("{\"answer\":true}");
        questionRepository.save(q1);

        Question q2 = new Question();
        q2.setType(QuestionType.TRUE_FALSE);
        q2.setDifficulty(Difficulty.EASY);
        q2.setQuestionText("Q2");
        q2.setContent("{\"answer\":false}");
        questionRepository.save(q2);

        mockMvc.perform(get("/api/v1/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[*].questionText", containsInAnyOrder("Q1", "Q2")));
    }

    @Test
    @DisplayName("GET /api/v1/questions?quizId=… → only that quiz’s questions")
    void listQuestions_filterByQuizId_returnsOnlyThatQuiz() throws Exception {
        questionRepository.deleteAll();
        quizRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category cat = new Category();
        cat.setName("General");
        cat.setDescription("Default");
        categoryRepository.save(cat);

        User admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@example.com");
        admin.setHashedPassword("pw");
        admin.setActive(true);
        userRepository.save(admin);

        Quiz quiz = new Quiz();
        quiz.setTitle("My Quiz");
        quiz.setDescription("desc");
        quiz.setDifficulty(Difficulty.EASY);
        quiz.setEstimatedTime(10);
        quiz.setTimerDuration(5);
        quiz.setIsTimerEnabled(false);
        quiz.setIsRepetitionEnabled(false);
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setCategory(cat);
        quiz.setCreator(admin);
        quizRepository.save(quiz);

        Question qA = new Question();
        qA.setType(QuestionType.TRUE_FALSE);
        qA.setDifficulty(Difficulty.EASY);
        qA.setQuestionText("A");
        qA.setContent("{\"answer\":true}");
        questionRepository.save(qA);

        Question qB = new Question();
        qB.setType(QuestionType.TRUE_FALSE);
        qB.setDifficulty(Difficulty.EASY);
        qB.setQuestionText("B");
        qB.setContent("{\"answer\":false}");
        questionRepository.save(qB);

        quiz.getQuestions().add(qA);
        quiz.getQuestions().add(qB);
        quizRepository.save(quiz);

        Question qOther = new Question();
        qOther.setType(QuestionType.TRUE_FALSE);
        qOther.setDifficulty(Difficulty.EASY);
        qOther.setQuestionText("Other");
        qOther.setContent("{\"answer\":true}");
        questionRepository.save(qOther);


        mockMvc.perform(get("/api/v1/questions")
                        .param("quizId", quiz.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[*].questionText", containsInAnyOrder("A", "B")))
                .andExpect(jsonPath("$.content[*].questionText", not(hasItem("Other"))));
    }

    @Test
    @DisplayName("GET /api/v1/questions?pageNumber=1&size=5 → pagination & sorting works")
    void listQuestions_paginationAndSorting() throws Exception {
        questionRepository.deleteAll();

        for (int i = 1; i <= 12; i++) {
            Question q = new Question();
            q.setType(QuestionType.TRUE_FALSE);
            q.setDifficulty(Difficulty.EASY);
            q.setQuestionText("Q" + i);
            q.setContent("{\"answer\":" + (i % 2 == 0) + "}");
            questionRepository.save(q);
            questionRepository.flush(); // Ensure each question gets a distinct timestamp

            // Add a small delay to ensure timestamps are distinct
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        mockMvc.perform(get("/api/v1/questions")
                        .param("pageNumber", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(5)))
                .andExpect(jsonPath("$.number", is(1)))
                .andExpect(jsonPath("$.totalElements", is(12)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.content[0].questionText", is("Q7")));
    }

    @Test
    @DisplayName("GET /api/v1/questions/{id} existing ID as anonymous returns 200 OK with QuestionDto")
    void getQuestion_existingId_anonymousReturns200() throws Exception {
        String payload = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Fetch me!",
                  "content":{"answer":true}
                }
                """;

        String response = mockMvc.perform(post("/api/v1/questions")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(response).get("questionId").asText());

        mockMvc.perform(get("/api/v1/questions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.type").value("TRUE_FALSE"))
                .andExpect(jsonPath("$.difficulty").value("EASY"))
                .andExpect(jsonPath("$.questionText").value("Fetch me!"))
                .andExpect(jsonPath("$.content.answer").value(true))
                .andExpect(jsonPath("$.hint").doesNotExist())
                .andExpect(jsonPath("$.explanation").doesNotExist())
                .andExpect(jsonPath("$.attachmentUrl").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/questions/{id} non-existent ID as anonymous returns 404 NOT_FOUND")
    void getQuestion_nonexistentId_anonymousReturns404() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/questions/{id}", missing))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/questions/{id} with all fields returns 200 OK with updated JSON")
    void updateQuestion_allFields_adminReturns200() throws Exception {
        String createJson = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Original?",
                  "content":{"answer":true}
                }
                """;
        String createResp = mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID qid = UUID.fromString(objectMapper.readTree(createResp).get("questionId").asText());

        String updateJson = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"HARD",
                  "questionText":"Updated?",
                  "content":{"answer":false},
                  "hint":"New hint",
                  "explanation":"New explanation",
                  "attachmentUrl":"http://example.com/img.png",
                  "quizIds":[],
                  "tagIds":[]
                }
                """;
        mockMvc.perform(patch("/api/v1/questions/{id}", qid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionText", is("Updated?")))
                .andExpect(jsonPath("$.difficulty", is("HARD")))
                .andExpect(jsonPath("$.hint", is("New hint")))
                .andExpect(jsonPath("$.explanation", is("New explanation")))
                .andExpect(jsonPath("$.attachmentUrl", is("http://example.com/img.png")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/questions/{id} with only required fields returns 200 OK")
    void updateQuestion_requiredFields_adminReturns200() throws Exception {
        String createJson = """
                {
                  "type":"MCQ_SINGLE",
                  "difficulty":"MEDIUM",
                  "questionText":"Pick one",
                  "content":{"options":[{"id":"a","text":"A","correct":false},{"id":"b","text":"B","correct":true}]}
                }
                """;
        String createResp = mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID qid = UUID.fromString(objectMapper.readTree(createResp).get("questionId").asText());

        String reqJson = """
                {
                  "type":"MCQ_SINGLE",
                  "difficulty":"MEDIUM",
                  "questionText":"Pick B",
                  "content":{"options":[{"id":"a","text":"A","correct":false},{"id":"b","text":"B","correct":true}]}
                }
                """;
        mockMvc.perform(patch("/api/v1/questions/{id}", qid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionText", is("Pick B")))
                .andExpect(jsonPath("$.hint").doesNotExist())
                .andExpect(jsonPath("$.explanation").doesNotExist())
                .andExpect(jsonPath("$.attachmentUrl").doesNotExist());
    }

    @Test
    @DisplayName("PATCH /api/v1/questions/{id} without authentication returns 401 UNAUTHORIZED")
    void updateQuestion_anonymousReturns401() throws Exception {
        UUID randomId = UUID.randomUUID();
        String body = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Should fail",
                  "content":{"answer":true}
                }
                """;
        mockMvc.perform(patch("/api/v1/questions/{id}", randomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("PATCH /api/v1/questions/{id} with USER role returns 403 FORBIDDEN")
    void updateQuestion_userRoleReturns403() throws Exception {
        String createJson = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Original?",
                  "content":{"answer":true}
                }
                """;
        String resp = mockMvc.perform(post("/api/v1/questions")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID qid = UUID.fromString(objectMapper.readTree(resp).get("questionId").asText());

        String updateJson = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"HARD",
                  "questionText":"Should not work",
                  "content":{"answer":false}
                }
                """;

        mockMvc.perform(patch("/api/v1/questions/{id}", qid)
                        .with(user("bob").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/questions/{id} nonexistent ID returns 404 NOT_FOUND")
    void updateQuestion_nonexistentIdReturns404() throws Exception {
        UUID missing = UUID.randomUUID();
        String body = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Doesn't matter",
                  "content":{"answer":true}
                }
                """;

        mockMvc.perform(patch("/api/v1/questions/{id}", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Question " + missing + " not found"))));
    }

    @ParameterizedTest(name = "{0}")
    @WithMockUser(roles = "ADMIN")
    @MethodSource("invalidPayloads")
    @DisplayName("PATCH /api/v1/questions/{id} invalid body returns 400 BAD_REQUEST")
    void updateQuestion_invalidPayloadReturns400(String name, String jsonPayload) throws Exception {
        String create = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Foo?",
                  "content":{"answer":true}
                }
                """;
        String resp = mockMvc.perform(post("/api/v1/questions")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID qid = UUID.fromString(objectMapper.readTree(resp).get("questionId").asText());

        mockMvc.perform(patch("/api/v1/questions/{id}", qid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/questions/{id} unknown quizIds returns 404 NOT_FOUND")
    void updateQuestion_unknownQuizIdsReturns404() throws Exception {
        String create = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Question?",
                  "content":{"answer":true}
                }
                """;
        String resp = mockMvc.perform(post("/api/v1/questions")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID qid = UUID.fromString(objectMapper.readTree(resp).get("questionId").asText());

        UUID badQuiz = UUID.randomUUID();
        String update = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"With bad quiz",
                  "content":{"answer":true},
                  "quizIds":["%s"]
                }
                """.formatted(badQuiz);

        mockMvc.perform(patch("/api/v1/questions/{id}", qid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Quiz " + badQuiz + " not found"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PATCH /api/v1/questions/{id} unknown tagIds returns 404 NOT_FOUND")
    void updateQuestion_unknownTagIdsReturns404() throws Exception {
        String create = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Question?",
                  "content":{"answer":true}
                }
                """;
        String resp = mockMvc.perform(post("/api/v1/questions")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID qid = UUID.fromString(objectMapper.readTree(resp).get("questionId").asText());

        UUID badTag = UUID.randomUUID();
        String update = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"With bad tag",
                  "content":{"answer":true},
                  "tagIds":["%s"]
                }
                """.formatted(badTag);

        mockMvc.perform(patch("/api/v1/questions/{id}", qid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Tag " + badTag + " not found"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createQuestionUnknownQuizId_thanReturns404() throws Exception {
        String badQuizId = UUID.randomUUID().toString();
        String payload = """
                {
                    "type":"TRUE_FALSE",
                    "difficulty":"EASY",
                    "questionText":"Question?",
                    "content":{"answer":true},
                    "quizIds":["%s"]
                }
                """.formatted(badQuizId);

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createQuestionUnknownTagId_thanReturns404() throws Exception {
        String badTagId = UUID.randomUUID().toString();
        String payload = """
                {
                    "type":"TRUE_FALSE",
                    "difficulty":"EASY",
                    "questionText":"Question?",
                    "content":{"answer":true},
                    "tagIds":["%s"]
                }
                """.formatted(badTagId);

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /api/v1/questions/{id} (ADMIN) existing → 204 and subsequent GET → 404")
    void deleteQuestion_existingId_adminReturns204ThenGet404() throws Exception {
        String createJson = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Delete me?",
                  "content":{"answer":true}
                }
                """;
        String resp = mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID qid = UUID.fromString(objectMapper.readTree(resp).get("questionId").asText());

        mockMvc.perform(delete("/api/v1/questions/{id}", qid))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/questions/{id}", qid))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /api/v1/questions/{id} (ADMIN) non-existent → 404")
    void deleteQuestion_nonexistentId_adminReturns404() throws Exception {
        mockMvc.perform(delete("/api/v1/questions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/questions/{id} (anonymous) → 403")
    void deleteQuestion_anonymousReturns403() throws Exception {
        Question q = new Question();
        q.setType(QuestionType.TRUE_FALSE);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Anon delete?");
        q.setContent("{\"answer\":true}");
        questionRepository.save(q);
        UUID qid = q.getId();

        mockMvc.perform(delete("/api/v1/questions/{id}", qid))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("DELETE /api/v1/questions/{id} (USER) → 403")
    void deleteQuestion_userRoleReturns403() throws Exception {
        Question q = new Question();
        q.setType(QuestionType.TRUE_FALSE);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("User delete?");
        q.setContent("{\"answer\":true}");
        questionRepository.save(q);
        UUID qid = q.getId();

        mockMvc.perform(delete("/api/v1/questions/{id}", qid))
                .andExpect(status().isForbidden());
    }
}
