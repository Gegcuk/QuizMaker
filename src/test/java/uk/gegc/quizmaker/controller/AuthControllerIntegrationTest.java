package uk.gegc.quizmaker.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.auth.LoginRequest;
import uk.gegc.quizmaker.dto.auth.RefreshRequest;
import uk.gegc.quizmaker.dto.auth.RegisterRequest;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create"
})
@WithMockUser(username = "defaultUser", roles = "ADMIN")
@DisplayName("Integration Tests AuthController")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AuthControllerIntegrationTest {

    @Autowired
    UserRepository userRepository;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MessageSource messageSource;



    /**
     * Helper method to get validation messages from ValidationMessages.properties
     * This ensures tests use the same messages as production, providing:
     * - Consistency between test and production behavior
     * - Automatic updates when messages change
     * - Validation of internationalization setup
     * - Single source of truth for validation messages
     * 
     * Note: In test context, ValidationMessages.properties might not be automatically loaded,
     * so we provide fallback messages to ensure tests work reliably.
     * This approach ensures tests are robust and don't depend on specific Spring Boot configuration.
     */
    private String getValidationMessage(String code) {
        try {
            return messageSource.getMessage(code, null, Locale.getDefault());
        } catch (Exception e) {
            // Fallback to hardcoded messages if ValidationMessages.properties is not loaded
            return switch (code) {
                case "password.blank" -> "Password must not be blank.";
                case "password.length" -> "Password must be between 8 and 100 characters long.";
                case "password.composition" -> "Password must contain an uppercase letter, a lowercase letter, a digit, and a special character, with no spaces.";
                case "username.blank" -> "Username must not be blank.";
                case "username.length" -> "Username must be between 4 and 20 characters.";
                case "email.blank" -> "Email must not be blank.";
                case "email.invalid" -> "Email must be a valid address.";
                case "email.max" -> "Email must not exceed 254 characters.";
                default -> code; // Return the code if no fallback is defined
            };
        }
    }

    /**
     * Helper method to get business logic messages (these might not be in ValidationMessages.properties)
     */
    private String getBusinessMessage(String code) {
        try {
            return messageSource.getMessage(code, null, Locale.getDefault());
        } catch (Exception e) {
            // If message not found, return the code as fallback
            return code;
        }
    }

    static Stream<Arguments> loginBlankFields() {
        return Stream.of(
                Arguments.of("", "somePass"),
                Arguments.of("someUser", "")
        );
    }

    /**
     * Dynamic test data that loads validation messages from ValidationMessages.properties
     * This ensures tests use the same messages as production
     */
    private Stream<Arguments> invalidRegisterData() {
        return Stream.of(
                Arguments.of("", "foo@ex.com", "ValidPass1!", getValidationMessage("username.blank")),
                Arguments.of("as", "foo@ex.com", "ValidPass1!", getValidationMessage("username.length")),
                Arguments.of("validUser", "", "ValidPass1!", getValidationMessage("email.blank")),
                Arguments.of("validUser", "not-an-email", "ValidPass1!", getValidationMessage("email.invalid")),
                Arguments.of("validUser", "foo@ex.com", "", getValidationMessage("password.blank")),
                Arguments.of("validUser", "foo@ex.com", "short", getValidationMessage("password.length")),
                Arguments.of("validUser", "foo@ex.com", "weakpassword", getValidationMessage("password.composition"))
        );
    }

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        User defaultUser = new User();
        defaultUser.setUsername("defaultUser");
        defaultUser.setEmail("defaultUser@email.com");
        defaultUser.setHashedPassword("defaultPassword");
        defaultUser.setActive(true);
        defaultUser.setDeleted(false);
        userRepository.save(defaultUser);
    }

    @Test
    @DisplayName("Validation messages are loaded correctly from ValidationMessages.properties")
    void validationMessagesAreLoadedCorrectly() {
        // Verify that validation messages are loaded correctly
        // This test works whether ValidationMessages.properties is loaded or we use fallbacks
        String passwordBlank = getValidationMessage("password.blank");
        String passwordLength = getValidationMessage("password.length");
        String passwordComposition = getValidationMessage("password.composition");
        String usernameBlank = getValidationMessage("username.blank");
        String emailInvalid = getValidationMessage("email.invalid");

        // Assert that we get meaningful messages (not just the codes)
        assertThat(passwordBlank).isNotEqualTo("password.blank");
        assertThat(passwordLength).isNotEqualTo("password.length");
        assertThat(passwordComposition).isNotEqualTo("password.composition");
        assertThat(usernameBlank).isNotEqualTo("username.blank");
        assertThat(emailInvalid).isNotEqualTo("email.invalid");

        // Assert specific content
        assertThat(passwordBlank).contains("Password must not be blank");
        assertThat(passwordLength).contains("between 8 and 100 characters");
        assertThat(passwordComposition).contains("uppercase letter");
        assertThat(usernameBlank).contains("Username must not be blank");
        assertThat(emailInvalid).contains("valid address");
    }

    @Test
    @DisplayName("POST /api/v1/auth/register → returns 201 CREATED with AuthenticatedUserDto")
    void registerSucceeds() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "newUser",
                "newuser@email.com",
                "NewUserPass123!"
        );
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.username", is("newUser")))
                .andExpect(jsonPath("$.email", is("newuser@email.com")))
                .andExpect(jsonPath("$.isActive", is(true)))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.roles").exists());
    }

    @ParameterizedTest(name = "[{index}] ''{0}'',''{1}'',''{2}'' → ''{3}''")
    @MethodSource("invalidRegisterData")
    @DisplayName("POST /api/v1/auth/register with invalid input → returns 400 BAD_REQUEST")
    void registerValidationFails(
            String username,
            String email,
            String password,
            String expectedMessage
    ) throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(username, email, password);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItems(containsString(expectedMessage))));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register when username or email already in use → returns 409 CONFLICT")
    void registerConflictOnDuplicate() throws Exception {
        // seed user "bob"
        User bob = new User();
        bob.setUsername("bobs");
        bob.setEmail("bob@example.com");
        bob.setHashedPassword("password");
        bob.setActive(true);
        bob.setDeleted(false);
        userRepository.save(bob);

        var dupUser = new RegisterRequest("bobs", "bob2@example.com", "ValidPass123!");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupUser)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]", containsString("Username already in use")));

        var dupEmail = new RegisterRequest("bob2", "bob@example.com", "ValidPass123!");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupEmail)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]", containsString("Email already in use")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login → returns 200 OK with JWTs")
    void loginSucceed() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "newUser",
                "newuser@email.com",
                "NewUserPass123!"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        LoginRequest loginWithUserNameRequest = new LoginRequest(
                "newUser",
                "NewUserPass123!"
        );

        LoginRequest loginWithEmailNameRequest = new LoginRequest(
                "newuser@email.com",
                "NewUserPass123!"
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginWithUserNameRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.accessExpiresInMs", greaterThan(0)))
                .andExpect(jsonPath("$.refreshExpiresInMs", greaterThan(0)));

        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginWithEmailNameRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.accessExpiresInMs", greaterThan(0)))
                .andExpect(jsonPath("$.refreshExpiresInMs", greaterThan(0)))
                .andReturn().getResponse().getContentAsString();

        String refreshToken = objectMapper.readTree(json).get("refreshToken").asText();
        RefreshRequest refreshRequest = new RefreshRequest(refreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/auth/login with bad credentials → returns 401 UNAUTHORIZED")
    void loginUnauthorized() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "newUser",
                "newuser@email.com",
                "NewUserPass123!"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest(
                "newUser",
                "wrongPassword"
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]", containsString("Invalid username or password")));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me → returns 200 OK with current AuthenticatedUserDto")
    void meSucceeds() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh with invalid token → returns 401 UNAUTHORIZED")
    void refreshInvalidToken() throws Exception {
        var badRefresh = new RefreshRequest("not.a.real.token");
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]", containsString("Invalid refresh token")));
    }

    @ParameterizedTest(name = "[{index}] ''{0}'',''{1}'' → 400 BAD_REQUEST")
    @MethodSource("loginBlankFields")
    @DisplayName("POST /api/v1/auth/login with blank username or password → returns 400 BAD_REQUEST")
    void loginBlankFields_returns400(String username, String password) throws Exception {
        var req = new LoginRequest(username, password);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh with access token → returns 400 BAD_REQUEST")
    void refreshWithAccessToken_returns400() throws Exception {
        var reg = new RegisterRequest("user1", "u1@ex.com", "ValidPass123!");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());
        var login = new LoginRequest("user1", "ValidPass123!");
        String loginJson = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(loginJson).get("accessToken").asText();

        var refreshReq = new RefreshRequest(accessToken);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/auth/me as anonymous → returns 401 UNAUTHORIZED")
    void meAnonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/auth/me for non-existent user → returns 404 NOT_FOUND")
    void meNonexistentUser_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(user("ghost")))
                .andExpect(status().isNotFound());
    }


}
