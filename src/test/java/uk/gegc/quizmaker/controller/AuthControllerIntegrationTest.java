package uk.gegc.quizmaker.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import uk.gegc.quizmaker.dto.auth.LoginRequest;
import uk.gegc.quizmaker.dto.auth.RefreshRequest;
import uk.gegc.quizmaker.dto.auth.RegisterRequest;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.util.stream.Stream;

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
public class AuthControllerIntegrationTest {

    @Autowired
    UserRepository userRepository;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> loginBlankFields() {
        return Stream.of(
                Arguments.of("", "somePass"),
                Arguments.of("someUser", "")
        );
    }

    private static Stream<Arguments> invalidRegisterDate() {
        return Stream.of(
                Arguments.of("", "foo@ex.com", "ValidPass1!", "Username must not be blank"),
                Arguments.of("as", "foo@ex.com", "ValidPass1!", "Username must be between 4 and 20 characters"),
                Arguments.of("validUser", "", "ValidPass1!", "Email must not be blank"),
                Arguments.of("validUser", "not-an-email", "ValidPass1!", "Email must be a valid address"),
                Arguments.of("validUser", "foo@ex.com", "", "Password must not be blank"),
                Arguments.of("validUser", "foo@ex.com", "short", "Password length must be at least 8 characters")
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
    @DisplayName("POST /api/v1/auth/register → returns 201 CREATED with UserDto")
    void registerSucceeds() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "newUser",
                "newuser@email.com",
                "newUserPassword"
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
    @MethodSource("invalidRegisterDate")
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
        // seed user “bob”
        User bob = new User();
        bob.setUsername("bobs");
        bob.setEmail("bob@example.com");
        bob.setHashedPassword("password");
        bob.setActive(true);
        bob.setDeleted(false);
        userRepository.save(bob);

        var dupUser = new RegisterRequest("bobs", "bob2@example.com", "Password1");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupUser)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]", containsString("Username already in use")));

        var dupEmail = new RegisterRequest("bob2", "bob@example.com", "Password1");
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
                "newUserPassword"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        LoginRequest loginWithUserNameRequest = new LoginRequest(
                "newUser",
                "newUserPassword"
        );

        LoginRequest loginWithEmailNameRequest = new LoginRequest(
                "newuser@email.com",
                "newUserPassword"
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
        var badUser = new LoginRequest("noone", "irrelevant");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badUser)))
                .andExpect(status().isUnauthorized());

        var reg = new RegisterRequest("dave", "dave@example.com", "RightPass!");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        var badPass = new LoginRequest("dave", "WrongPass!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badPass)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/auth/me → returns 200 OK with current UserDto")
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
                .andExpect(status().isConflict())
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
        var reg = new RegisterRequest("user1", "u1@ex.com", "Password1!");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());
        var login = new LoginRequest("user1", "Password1!");
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
                .andExpect(status().isConflict());
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
