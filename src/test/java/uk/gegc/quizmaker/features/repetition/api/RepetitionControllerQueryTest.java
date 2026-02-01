package uk.gegc.quizmaker.features.repetition.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.repetition.application.RepetitionQueryService;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReminderService;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReviewService;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionEntryDto;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionHistoryDto;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewSourceType;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.testsupport.WebMvcSecurityTestConfig;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RepetitionController.class)
@Import(WebMvcSecurityTestConfig.class)
@DisplayName("RepetitionController Query Endpoints Tests")
class RepetitionControllerQueryTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_A_ID = UUID.fromString("10000000-0000-0000-0000-00000000000A");
    private static final UUID USER_EMAIL_ID = UUID.fromString("10000000-0000-0000-0000-0000000000E1");
    private static final UUID ENTRY_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID QUESTION_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID REVIEW_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final Instant NEXT_REVIEW = Instant.parse("2025-03-01T12:00:00Z");
    private static final Instant LAST_REVIEWED = Instant.parse("2025-02-01T12:00:00Z");
    private static final Instant REVIEWED_AT = Instant.parse("2025-02-01T10:00:00Z");

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

    @BeforeEach
    void setUp() {
        User testUser = new User();
        testUser.setId(USER_ID);
        testUser.setUsername("testuser");
        testUser.setEmail("testuser@example.com");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(userRepository.findByEmail("testuser")).thenReturn(Optional.of(testUser));

        User userA = new User();
        userA.setId(USER_A_ID);
        userA.setUsername("userA");
        when(userRepository.findByUsername("userA")).thenReturn(Optional.of(userA));
        when(userRepository.findByEmail("userA")).thenReturn(Optional.empty());

        User userByEmail = new User();
        userByEmail.setId(USER_EMAIL_ID);
        userByEmail.setEmail("user@example.com");
        when(userRepository.findByUsername("user@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(userByEmail));

        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("unknown")).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: when authorized then returns 200")
    @WithMockUser(username = "testuser")
    void getDue_authorized_returns200() throws Exception {
        when(repetitionQueryService.getDueEntries(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/repetition/due"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: returns expected JSON shape")
    @WithMockUser(username = "testuser")
    void getDue_returnsExpectedJsonShape() throws Exception {
        RepetitionEntryDto dto = new RepetitionEntryDto(
                ENTRY_ID, QUESTION_ID, NEXT_REVIEW, LAST_REVIEWED, RepetitionEntryGrade.GOOD,
                6, 1, 2.5, true, 25);
        when(repetitionQueryService.getDueEntries(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/repetition/due"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].entryId").value(ENTRY_ID.toString()))
                .andExpect(jsonPath("$.content[0].questionId").value(QUESTION_ID.toString()))
                .andExpect(jsonPath("$.content[0].nextReviewAt").exists())
                .andExpect(jsonPath("$.content[0].lastReviewedAt").exists())
                .andExpect(jsonPath("$.content[0].lastGrade").value("GOOD"))
                .andExpect(jsonPath("$.content[0].intervalDays").value(6))
                .andExpect(jsonPath("$.content[0].repetitionCount").value(1))
                .andExpect(jsonPath("$.content[0].easeFactor").value(2.5))
                .andExpect(jsonPath("$.content[0].reminderEnabled").value(true))
                .andExpect(jsonPath("$.content[0].priorityScore").value(25))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: respects pageable and sort params")
    @WithMockUser(username = "testuser")
    void getDue_respectsPageableAndSort() throws Exception {
        when(repetitionQueryService.getDueEntries(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/repetition/due")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "nextReviewAt,desc"))
                .andExpect(status().isOk());

        verify(repetitionQueryService).getDueEntries(eq(USER_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: without authentication then returns 401")
    void getDue_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/due"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: resolves UUID principal directly")
    @WithMockUser(username = "00000000-0000-0000-0000-000000000000")
    void getDue_uuidPrincipal_resolvesUserId() throws Exception {
        UUID uuidPrincipal = UUID.fromString("00000000-0000-0000-0000-000000000000");
        when(repetitionQueryService.getDueEntries(eq(uuidPrincipal), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/repetition/due"))
                .andExpect(status().isOk());

        verify(repetitionQueryService).getDueEntries(eq(uuidPrincipal), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: resolves username via UserRepository")
    @WithMockUser(username = "userA")
    void getDue_usernamePrincipal_resolvesUserId() throws Exception {
        when(repetitionQueryService.getDueEntries(eq(USER_A_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/repetition/due"))
                .andExpect(status().isOk());

        verify(repetitionQueryService).getDueEntries(eq(USER_A_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: resolves email via UserRepository")
    @WithMockUser(username = "user@example.com")
    void getDue_emailPrincipal_resolvesUserId() throws Exception {
        when(repetitionQueryService.getDueEntries(eq(USER_EMAIL_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/repetition/due"))
                .andExpect(status().isOk());

        verify(repetitionQueryService).getDueEntries(eq(USER_EMAIL_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/due: unknown principal returns 401")
    @WithMockUser(username = "unknown")
    void getDue_unknownPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/due"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: when authorized then returns 200")
    @WithMockUser(username = "testuser")
    void getPriority_authorized_returns200() throws Exception {
        when(repetitionQueryService.getPriorityQueue(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/repetition/priority"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: returns expected JSON shape")
    @WithMockUser(username = "testuser")
    void getPriority_returnsExpectedJsonShape() throws Exception {
        RepetitionEntryDto dto = new RepetitionEntryDto(
                ENTRY_ID, QUESTION_ID, NEXT_REVIEW, LAST_REVIEWED, RepetitionEntryGrade.EASY,
                10, 2, 2.6, true, 15);
        when(repetitionQueryService.getPriorityQueue(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/repetition/priority"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].entryId").value(ENTRY_ID.toString()))
                .andExpect(jsonPath("$.content[0].questionId").value(QUESTION_ID.toString()))
                .andExpect(jsonPath("$.content[0].priorityScore").value(15))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: respects pageable and sort params")
    @WithMockUser(username = "testuser")
    void getPriority_respectsPageableAndSort() throws Exception {
        when(repetitionQueryService.getPriorityQueue(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/repetition/priority")
                        .param("page", "0")
                        .param("size", "10")
                        .param("sort", "nextReviewAt,asc"))
                .andExpect(status().isOk());

        verify(repetitionQueryService).getPriorityQueue(eq(USER_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: without authentication then returns 401")
    void getPriority_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/priority"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/priority: unknown principal returns 401")
    @WithMockUser(username = "unknown")
    void getPriority_unknownPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/priority"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: when authorized then returns 200")
    @WithMockUser(username = "testuser")
    void getHistory_authorized_returns200() throws Exception {
        when(repetitionQueryService.getHistory(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/repetition/history"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: returns expected JSON shape")
    @WithMockUser(username = "testuser")
    void getHistory_returnsExpectedJsonShape() throws Exception {
        RepetitionHistoryDto dto = new RepetitionHistoryDto(
                REVIEW_ID, ENTRY_ID, RepetitionContentType.QUESTION, QUESTION_ID,
                RepetitionEntryGrade.GOOD, REVIEWED_AT, 6, 2.5, 1,
                RepetitionReviewSourceType.MANUAL_REVIEW, null, null);
        when(repetitionQueryService.getHistory(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/repetition/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].reviewId").value(REVIEW_ID.toString()))
                .andExpect(jsonPath("$.content[0].entryId").value(ENTRY_ID.toString()))
                .andExpect(jsonPath("$.content[0].contentType").value("QUESTION"))
                .andExpect(jsonPath("$.content[0].contentId").value(QUESTION_ID.toString()))
                .andExpect(jsonPath("$.content[0].grade").value("GOOD"))
                .andExpect(jsonPath("$.content[0].reviewedAt").exists())
                .andExpect(jsonPath("$.content[0].intervalDays").value(6))
                .andExpect(jsonPath("$.content[0].easeFactor").value(2.5))
                .andExpect(jsonPath("$.content[0].repetitionCount").value(1))
                .andExpect(jsonPath("$.content[0].sourceType").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: respects pageable and sort params")
    @WithMockUser(username = "testuser")
    void getHistory_respectsPageableAndSort() throws Exception {
        when(repetitionQueryService.getHistory(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/repetition/history")
                        .param("page", "2")
                        .param("size", "15")
                        .param("sort", "reviewedAt,desc"))
                .andExpect(status().isOk());

        verify(repetitionQueryService).getHistory(eq(USER_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: without authentication then returns 401")
    void getHistory_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/repetition/history: unknown principal returns 401")
    @WithMockUser(username = "unknown")
    void getHistory_unknownPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/repetition/history"))
                .andExpect(status().isUnauthorized());
    }
}
