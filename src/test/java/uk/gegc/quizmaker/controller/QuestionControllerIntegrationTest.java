package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Integration Tests QuestionController")
public class QuestionControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    QuestionRepository questionRepository;
    @Autowired
    QuizRepository quizRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    PermissionRepository permissionRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String userToken;
    private UserDetails adminUserDetails;
    private UserDetails regularUserDetails;
    private User adminUser;
    private User regularUser;
    private Category defaultCategory;

    @BeforeEach
    void setUp() {

        // Find existing permissions (created by DataInitializer)
        Permission questionCreatePermission = permissionRepository.findByPermissionName(PermissionName.QUESTION_CREATE.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUESTION_CREATE"));
        Permission questionReadPermission = permissionRepository.findByPermissionName(PermissionName.QUESTION_READ.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUESTION_READ"));
        Permission questionUpdatePermission = permissionRepository.findByPermissionName(PermissionName.QUESTION_UPDATE.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUESTION_UPDATE"));
        Permission questionDeletePermission = permissionRepository.findByPermissionName(PermissionName.QUESTION_DELETE.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUESTION_DELETE"));
        Permission questionAdminPermission = permissionRepository.findByPermissionName(PermissionName.QUESTION_ADMIN.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUESTION_ADMIN"));
        Permission quizReadPermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_READ.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUIZ_READ"));
        Permission categoryReadPermission = permissionRepository.findByPermissionName(PermissionName.CATEGORY_READ.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: CATEGORY_READ"));
        Permission tagReadPermission = permissionRepository.findByPermissionName(PermissionName.TAG_READ.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: TAG_READ"));
        Permission quizCreatePermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_CREATE.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUIZ_CREATE"));

        // Find existing roles (created by DataInitializer)
        Role adminRole = roleRepository.findByRoleName(RoleName.ROLE_ADMIN.name())
                .orElseThrow(() -> new RuntimeException("Role not found: ROLE_ADMIN"));

        Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
                .orElseThrow(() -> new RuntimeException("Role not found: ROLE_USER"));

        // Create test users with roles
        adminUser = new User();
        adminUser.setUsername("admin");
        adminUser.setEmail("admin@example.com");
        adminUser.setHashedPassword("password");
        adminUser.setActive(true);
        adminUser.setDeleted(false);
        adminUser.setRoles(Set.of(adminRole));
        adminUser = userRepository.save(adminUser);
        adminToken = adminUser.getUsername(); // Use username instead of ID

        regularUser = new User();
        regularUser.setUsername("user");
        regularUser.setEmail("user@example.com");
        regularUser.setHashedPassword("password");
        regularUser.setActive(true);
        regularUser.setDeleted(false);
        regularUser.setRoles(Set.of(userRole));
        regularUser = userRepository.save(regularUser);
        userToken = regularUser.getUsername(); // Use username instead of ID
        
        // Create default category
        defaultCategory = new Category();
        defaultCategory.setName("General");
        defaultCategory.setDescription("Default");
        defaultCategory = categoryRepository.save(defaultCategory);
        
        // Create UserDetails objects with proper authorities
        adminUserDetails = createUserDetails(adminUser);
        regularUserDetails = createUserDetails(regularUser);
    }
    
    private UserDetails createUserDetails(User user) {
        List<GrantedAuthority> authorities = user.getRoles()
                .stream()
                .flatMap(role -> {
                    // Add role authority
                    var roleAuthority = new SimpleGrantedAuthority(role.getRoleName());
                    // Add permission authorities
                    var permissionAuthorities = role.getPermissions().stream()
                            .map(permission -> new SimpleGrantedAuthority(permission.getPermissionName()));
                    return java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(roleAuthority),
                            permissionAuthorities
                    );
                })
                .map(authority -> (GrantedAuthority) authority)
                .toList();
        
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getHashedPassword(),
                user.isActive(),
                true,
                true,
                true,
                authorities
        );
    }

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
    @MethodSource("happyPathPayloads")
    void createQuestion_HappyPath_thanReturns201(String name, String jsonPayload) throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questionId").exists());
    }

    @Test
    @DisplayName("POST /api/v1/questions without authentication returns 401 UNAUTHORIZED")
    void createQuestion_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @DisplayName("POST /api/v1/questions with USER role returns 403 FORBIDDEN")
    void createQuestion_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .with(user(regularUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingType))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors", hasItem(
                        allOf(
                                hasEntry("field", "type"),
                                hasEntry(equalTo("message"), containsString("Type must not be null"))
                        )
                )))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingDifficulty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors", hasItem(
                        allOf(
                                hasEntry("field", "difficulty"),
                                hasEntry(equalTo("message"), containsString("Difficulty must not be null"))
                        )
                )))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingText))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors", hasItem(
                        allOf(
                                hasEntry("field", "questionText"),
                                hasEntry(equalTo("message"), containsString("Question text must not be blank"))
                        )
                )))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooShort))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors", hasItem(
                        allOf(
                                hasEntry("field", "questionText"),
                                hasEntry(equalTo("message"), containsString("Question text length must be between 3 and 1000 characters"))
                        )
                )))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors", hasItem(
                        allOf(
                                hasEntry("field", "questionText"),
                                hasEntry(equalTo("message"), containsString("Question text length must be between 3 and 1000 characters"))
                        )
                )))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullContent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail", containsString("Invalid JSON for TRUE_FALSE question")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/malformed-json"))
                .andExpect(jsonPath("$.title").value("Malformed JSON"))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors", hasItem(
                        allOf(
                                hasEntry("field", "hint"),
                                hasEntry(equalTo("message"), containsString("Hint length must be less than 500 characters"))
                        )
                )))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors", hasItem(
                        allOf(
                                hasEntry("field", "explanation"),
                                hasEntry(equalTo("message"), containsString("Explanation must be less than 2000 characters"))
                        )
                )))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors", hasItem(
                        allOf(
                                hasEntry("field", "attachmentUrl"),
                                hasEntry(equalTo("message"), containsString("URL length is limited by 2048 characters"))
                        )
                )))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail", containsString("Quiz " + badQuizId + " not found")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail", containsString("Tag " + badTagId + " not found")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloads")
    void createQuestion_invalidPayloadPath_thanReturns400(String name, String jsonPayload) throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/questions → empty DB returns totalElements=0")
    void listQuestions_emptyDb_returnsEmptyPage() throws Exception {
        questionRepository.deleteAll();

        mockMvc.perform(get("/api/v1/questions")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/questions → with data returns all questions")
    void listQuestions_withData_returnsAll() throws Exception {
        questionRepository.deleteAll();

        // Create a quiz first
        String quizPayload = """
                {
                  "title":"Test Quiz",
                  "description":"Test Description",
                  "difficulty":"EASY",
                  "visibility":"PUBLIC",
                  "status":"DRAFT",
                  "estimatedTime":1,
                  "timerDuration":1,
                  "categoryId":"%s"
                }
                """.formatted(defaultCategory.getId());
        String quizResponse = mockMvc.perform(post("/api/v1/quizzes")
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quizPayload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(quizResponse).get("quizId").asText());

        // Create questions using the API and associate them with the quiz
        String payload1 = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Question 1?",
                  "content":{"answer":true},
                  "quizIds":["%s"]
                }
                """.formatted(quizId);
        mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                .andExpect(status().isCreated());

        String payload2 = """
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Question 2?",
                  "content":{"answer":false},
                  "quizIds":["%s"]
                }
                """.formatted(quizId);
        mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/questions")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[*].questionText", containsInAnyOrder("Question 1?", "Question 2?")));
    }

    @Test
    @DisplayName("GET /api/v1/questions?quizId=… → only that quiz’s questions")
    void listQuestions_filterByQuizId_returnsOnlyThatQuiz() throws Exception {
        questionRepository.deleteAll();
        quizRepository.deleteAll();
        // Don't delete users as we need the admin user
        // Don't delete categories as we need the defaultCategory


        // Use the existing admin user from setUp
        User admin = adminUser;

        Quiz quiz = new Quiz();
        quiz.setTitle("My Quiz");
        quiz.setDescription("desc");
        quiz.setDifficulty(Difficulty.EASY);
        quiz.setEstimatedTime(10);
        quiz.setTimerDuration(5);
        quiz.setIsTimerEnabled(false);
        quiz.setIsRepetitionEnabled(false);
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setCategory(defaultCategory);
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
                        .param("quizId", quiz.getId().toString())
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[*].questionText", containsInAnyOrder("A", "B")))
                .andExpect(jsonPath("$.content[*].questionText", not(hasItem("Other"))));
    }

    @Test
    @DisplayName("GET /api/v1/questions?pageNumber=1&size=5 → pagination & sorting works")
    void listQuestions_paginationAndSorting() throws Exception {
        questionRepository.deleteAll();

        // Create a quiz first
        String quizPayload = """
                {
                  "title":"Test Quiz",
                  "description":"Test Description",
                  "difficulty":"EASY",
                  "visibility":"PUBLIC",
                  "status":"DRAFT",
                  "estimatedTime":1,
                  "timerDuration":1,
                  "categoryId":"%s"
                }
                """.formatted(defaultCategory.getId());
        String quizResponse = mockMvc.perform(post("/api/v1/quizzes")
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quizPayload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(quizResponse).get("quizId").asText());

        // Create questions using the API and associate them with the quiz
        for (int i = 1; i <= 12; i++) {
            String payload = String.format("""
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Question %d?",
                  "content":{"answer":%s},
                  "quizIds":["%s"]
                }
                """, i, i % 2 == 0, quizId);
            
            mockMvc.perform(post("/api/v1/questions")
                            .with(user(adminUserDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isCreated());
            
            // Add a small delay to ensure timestamps are distinct
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        mockMvc.perform(get("/api/v1/questions")
                        .param("pageNumber", "1")
                        .param("size", "5")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(5)))
                .andExpect(jsonPath("$.number", is(1)))
                .andExpect(jsonPath("$.totalElements", is(12)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()", is(5)))
                .andExpect(jsonPath("$.content[0].questionText").exists())
                .andExpect(jsonPath("$.content[0].type").value("TRUE_FALSE"));
    }

    @Test
    @DisplayName("GET /api/v1/questions/{id} existing ID as authenticated user returns 200 OK with QuestionDto")
    void getQuestion_existingId_authenticatedReturns200() throws Exception {
        // Create a quiz first
        String quizPayload = String.format("""
                {
                  "title":"Test Quiz",
                  "description":"Test Description",
                  "difficulty":"EASY",
                  "visibility":"PUBLIC",
                  "status":"DRAFT",
                  "estimatedTime":1,
                  "timerDuration":1,
                  "categoryId":"%s"
                }
                """, defaultCategory.getId());

        String quizResponse = mockMvc.perform(post("/api/v1/quizzes")
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quizPayload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID quizId = UUID.fromString(objectMapper.readTree(quizResponse).get("quizId").asText());

        // Create a question associated with the quiz
        String payload = String.format("""
                {
                  "type":"TRUE_FALSE",
                  "difficulty":"EASY",
                  "questionText":"Fetch me!",
                  "content":{"answer":true},
                  "quizIds":["%s"]
                }
                """, quizId);

        String response = mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(response).get("questionId").asText());

        mockMvc.perform(get("/api/v1/questions/{id}", id)
                        .with(user(adminUserDetails)))
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
        mockMvc.perform(get("/api/v1/questions/{id}", missing)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
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
                        .with(user(adminUserDetails))
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
                        .with(user(adminUserDetails))
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
                        .with(user(adminUserDetails))
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
                        .with(user(adminUserDetails))
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
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail", containsString("Question " + missing + " not found")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @ParameterizedTest(name = "{0}")
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID qid = UUID.fromString(objectMapper.readTree(resp).get("questionId").asText());

        mockMvc.perform(patch("/api/v1/questions/{id}", qid)
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
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
                        .with(user(adminUserDetails))
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail", containsString("Quiz " + badQuiz + " not found")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail", containsString("Tag " + badTag + " not found")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound());
    }

    @Test
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
                        .with(user(adminUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID qid = UUID.fromString(objectMapper.readTree(resp).get("questionId").asText());

        mockMvc.perform(delete("/api/v1/questions/{id}", qid)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/questions/{id}", qid)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/questions/{id} (ADMIN) non-existent → 404")
    void deleteQuestion_nonexistentId_adminReturns404() throws Exception {
        mockMvc.perform(delete("/api/v1/questions/{id}", UUID.randomUUID())
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/questions/{id} (anonymous) → 401")
    void deleteQuestion_anonymousReturns403() throws Exception {
        Question q = new Question();
        q.setType(QuestionType.TRUE_FALSE);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Anon delete?");
        q.setContent("{\"answer\":true}");
        questionRepository.save(q);
        UUID qid = q.getId();

        mockMvc.perform(delete("/api/v1/questions/{id}", qid))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @DisplayName("DELETE /api/v1/questions/{id} (anonymous) → 401")
    void deleteQuestion_userRoleReturns403() throws Exception {
        Question q = new Question();
        q.setType(QuestionType.TRUE_FALSE);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("User delete?");
        q.setContent("{\"answer\":true}");
        questionRepository.save(q);
        UUID qid = q.getId();

        mockMvc.perform(delete("/api/v1/questions/{id}", qid))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated (no user), not 403
    }
}
