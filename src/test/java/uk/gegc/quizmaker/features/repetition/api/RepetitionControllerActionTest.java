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
@DisplayName("RepetitionController Action Endpoints Tests")
class RepetitionControllerActionTest {

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
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: when valid then returns 200")
    @WithMockUser(username = "testuser")
    void reviewEntry_valid_returns200() {
        // TODO: implement
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: missing grade returns 400")
    @WithMockUser(username = "testuser")
    void reviewEntry_missingGrade_returns400() {
        // TODO: implement
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: invalid grade returns 400")
    @WithMockUser(username = "testuser")
    void reviewEntry_invalidGrade_returns400() {
        // TODO: implement
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: entry not found returns 404")
    @WithMockUser(username = "testuser")
    void reviewEntry_notFound_returns404() {
        // TODO: implement
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: idempotency conflict returns 409")
    @WithMockUser(username = "testuser")
    void reviewEntry_idempotencyConflict_returns409() {
        // TODO: implement
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: invalid entryId returns 400")
    @WithMockUser(username = "testuser")
    void reviewEntry_invalidEntryId_returns400() {
        // TODO: implement
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: without authentication then returns 401")
    void reviewEntry_noAuth_returns401() {
        // TODO: implement
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: when valid then returns 200")
    @WithMockUser(username = "testuser")
    void setReminder_valid_returns200() {
        // TODO: implement
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: missing enabled returns 400")
    @WithMockUser(username = "testuser")
    void setReminder_missingEnabled_returns400() {
        // TODO: implement
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: entry not found returns 404")
    @WithMockUser(username = "testuser")
    void setReminder_notFound_returns404() {
        // TODO: implement
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: invalid entryId returns 400")
    @WithMockUser(username = "testuser")
    void setReminder_invalidEntryId_returns400() {
        // TODO: implement
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: without authentication then returns 401")
    void setReminder_noAuth_returns401() {
        // TODO: implement
    }
}
