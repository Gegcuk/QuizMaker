package uk.gegc.quizmaker.features.repetition.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.repetition.application.RepetitionQueryService;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReminderService;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReviewService;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.testsupport.WebMvcSecurityTestConfig;

@WebMvcTest(RepetitionController.class)
@Import(WebMvcSecurityTestConfig.class)
@DisplayName("RepetitionController Query Endpoints Tests")
class RepetitionControllerQueryTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepetitionQueryService repetitionQueryService;

    @MockitoBean
    private RepetitionReviewService repetitionReviewService;

    @MockitoBean
    private RepetitionReminderService repetitionReminderService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("GET /api/v1/repetition/due: when authorized then returns 200")
    @WithMockUser(username = "testuser")
    void getDue_authorized_returns200() {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: without authentication then returns 401")
    void getDue_noAuth_returns401() {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: resolves UUID principal directly")
    @WithMockUser(username = "00000000-0000-0000-0000-000000000000")
    void getDue_uuidPrincipal_resolvesUserId() {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: resolves username via UserRepository")
    @WithMockUser(username = "userA")
    void getDue_usernamePrincipal_resolvesUserId() {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: resolves email via UserRepository")
    @WithMockUser(username = "user@example.com")
    void getDue_emailPrincipal_resolvesUserId() {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: unknown principal returns 401")
    @WithMockUser(username = "unknown")
    void getDue_unknownPrincipal_returns401() {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: when authorized then returns 200")
    @WithMockUser(username = "testuser")
    void getPriority_authorized_returns200() {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: without authentication then returns 401")
    void getPriority_noAuth_returns401() {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: when authorized then returns 200")
    @WithMockUser(username = "testuser")
    void getHistory_authorized_returns200() {
        // TODO: implement
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: without authentication then returns 401")
    void getHistory_noAuth_returns401() {
        // TODO: implement
    }
}
