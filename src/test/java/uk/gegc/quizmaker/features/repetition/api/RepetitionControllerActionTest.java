package uk.gegc.quizmaker.features.repetition.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.repetition.application.RepetitionQueryService;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReminderService;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReviewService;
import uk.gegc.quizmaker.features.repetition.application.exception.RepetitionAlreadyProcessedException;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.testsupport.WebMvcSecurityTestConfig;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RepetitionController.class)
@Import(WebMvcSecurityTestConfig.class)
@DisplayName("RepetitionController Action Endpoints Tests")
class RepetitionControllerActionTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ENTRY_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NEXT_REVIEW = Instant.parse("2025-03-01T12:00:00Z");
    private static final Instant LAST_REVIEWED = Instant.parse("2025-02-01T12:00:00Z");

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

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(USER_ID);
        testUser.setUsername("testuser");
        testUser.setEmail("testuser@example.com");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(userRepository.findByEmail("testuser")).thenReturn(Optional.of(testUser));
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("unknown")).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: when valid then returns 200")
    @WithMockUser(username = "testuser")
    void reviewEntry_valid_returns200() throws Exception {
        SpacedRepetitionEntry entry = entryWithSchedule();
        when(repetitionReviewService.reviewEntry(eq(ENTRY_ID), eq(USER_ID), eq(RepetitionEntryGrade.GOOD), eq(null)))
                .thenReturn(entry);

        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"GOOD\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: returns expected JSON shape")
    @WithMockUser(username = "testuser")
    void reviewEntry_returnsExpectedJsonShape() throws Exception {
        SpacedRepetitionEntry entry = entryWithSchedule();
        when(repetitionReviewService.reviewEntry(eq(ENTRY_ID), eq(USER_ID), eq(RepetitionEntryGrade.EASY), any()))
                .thenReturn(entry);

        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"EASY\",\"idempotencyKey\":\"30000000-0000-0000-0000-000000000003\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryId").value(ENTRY_ID.toString()))
                .andExpect(jsonPath("$.nextReviewAt").exists())
                .andExpect(jsonPath("$.intervalDays").value(6))
                .andExpect(jsonPath("$.repetitionCount").value(1))
                .andExpect(jsonPath("$.easeFactor").value(2.5))
                .andExpect(jsonPath("$.lastReviewedAt").exists())
                .andExpect(jsonPath("$.lastGrade").value("EASY"));
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: missing grade returns 400")
    @WithMockUser(username = "testuser")
    void reviewEntry_missingGrade_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"30000000-0000-0000-0000-000000000003\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: empty body returns 400")
    @WithMockUser(username = "testuser")
    void reviewEntry_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: malformed JSON returns 400")
    @WithMockUser(username = "testuser")
    void reviewEntry_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"grade\": \"GOOD\" "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: invalid idempotencyKey UUID returns 400")
    @WithMockUser(username = "testuser")
    void reviewEntry_invalidIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"GOOD\",\"idempotencyKey\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: invalid grade returns 400")
    @WithMockUser(username = "testuser")
    void reviewEntry_invalidGrade_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"INVALID_GRADE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: entry not found returns 404")
    @WithMockUser(username = "testuser")
    void reviewEntry_notFound_returns404() throws Exception {
        when(repetitionReviewService.reviewEntry(eq(ENTRY_ID), eq(USER_ID), eq(RepetitionEntryGrade.GOOD), any()))
                .thenThrow(new ResourceNotFoundException("Entry " + ENTRY_ID + " not found for user " + USER_ID));

        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"GOOD\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: idempotency conflict returns 409")
    @WithMockUser(username = "testuser")
    void reviewEntry_idempotencyConflict_returns409() throws Exception {
        UUID idemKey = UUID.fromString("30000000-0000-0000-0000-000000000003");
        when(repetitionReviewService.reviewEntry(eq(ENTRY_ID), eq(USER_ID), eq(RepetitionEntryGrade.GOOD), eq(idemKey)))
                .thenThrow(new RepetitionAlreadyProcessedException("Manual review already processed for key " + idemKey));

        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"GOOD\",\"idempotencyKey\":\"30000000-0000-0000-0000-000000000003\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: invalid entryId returns 400")
    @WithMockUser(username = "testuser")
    void reviewEntry_invalidEntryId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", "not-a-uuid")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"GOOD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: unknown principal returns 401")
    @WithMockUser(username = "unknown")
    void reviewEntry_unknownPrincipal_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"GOOD\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: without CSRF then returns 403")
    @WithMockUser(username = "testuser")
    void reviewEntry_noCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"GOOD\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/repetition/entries/{entryId}/review: without authentication then returns 401")
    void reviewEntry_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/repetition/entries/{entryId}/review", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"GOOD\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: when valid then returns 200")
    @WithMockUser(username = "testuser")
    void setReminder_valid_returns200() throws Exception {
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setId(ENTRY_ID);
        entry.setReminderEnabled(false);
        when(repetitionReminderService.setReminderEnabled(eq(ENTRY_ID), eq(USER_ID), eq(false)))
                .thenReturn(entry);

        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: returns expected JSON shape")
    @WithMockUser(username = "testuser")
    void setReminder_returnsExpectedJsonShape() throws Exception {
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setId(ENTRY_ID);
        entry.setReminderEnabled(true);
        when(repetitionReminderService.setReminderEnabled(eq(ENTRY_ID), eq(USER_ID), eq(true)))
                .thenReturn(entry);

        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryId").value(ENTRY_ID.toString()))
                .andExpect(jsonPath("$.reminderEnabled").value(true));
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: missing enabled returns 400")
    @WithMockUser(username = "testuser")
    void setReminder_missingEnabled_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: empty body returns 400")
    @WithMockUser(username = "testuser")
    void setReminder_emptyBody_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: malformed JSON returns 400")
    @WithMockUser(username = "testuser")
    void setReminder_malformedJson_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"enabled\": true "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: entry not found returns 404")
    @WithMockUser(username = "testuser")
    void setReminder_notFound_returns404() throws Exception {
        when(repetitionReminderService.setReminderEnabled(eq(ENTRY_ID), eq(USER_ID), eq(true)))
                .thenThrow(new ResourceNotFoundException("Entry " + ENTRY_ID + " not found for user " + USER_ID));

        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: invalid entryId returns 400")
    @WithMockUser(username = "testuser")
    void setReminder_invalidEntryId_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", "not-a-uuid")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: unknown principal returns 401")
    @WithMockUser(username = "unknown")
    void setReminder_unknownPrincipal_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: without CSRF then returns 403")
    @WithMockUser(username = "testuser")
    void setReminder_noCsrf_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/v1/repetition/entries/{entryId}/reminder: without authentication then returns 401")
    void setReminder_noAuth_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/repetition/entries/{entryId}/reminder", ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
    }

    private static SpacedRepetitionEntry entryWithSchedule() {
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setId(ENTRY_ID);
        entry.setNextReviewAt(NEXT_REVIEW);
        entry.setIntervalDays(6);
        entry.setRepetitionCount(1);
        entry.setEaseFactor(2.5);
        entry.setLastReviewedAt(LAST_REVIEWED);
        entry.setLastGrade(RepetitionEntryGrade.EASY);
        return entry;
    }
}
