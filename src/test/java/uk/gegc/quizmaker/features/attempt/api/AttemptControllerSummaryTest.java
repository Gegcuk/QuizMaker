package uk.gegc.quizmaker.features.attempt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptStatsDto;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptSummaryDto;
import uk.gegc.quizmaker.features.attempt.api.dto.QuizSummaryDto;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.testsupport.WebMvcSecurityTestConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AttemptController.class)
@Import(WebMvcSecurityTestConfig.class)
@DisplayName("AttemptController Summary Endpoint Tests")
class AttemptControllerSummaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AttemptService attemptService;

    @Test
    @DisplayName("GET /api/v1/attempts/summary: when authorized then returns 200 with enriched data")
    @WithMockUser(username = "testuser")
    void getAttemptsSummary_authorized_returns200() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        QuizSummaryDto quizSummary = new QuizSummaryDto(
                quizId,
                "Java Fundamentals Quiz",
                10,
                categoryId,
                true
        );

        AttemptStatsDto stats = new AttemptStatsDto(
                attemptId,
                Duration.ofMinutes(15),
                Duration.ofMinutes(1).plusSeconds(30),
                10,
                8,
                80.0,
                100.0,
                null,  // No detailed timings in summary
                Instant.now().minusSeconds(900),
                Instant.now()
        );

        AttemptSummaryDto summary = new AttemptSummaryDto(
                attemptId,
                quizId,
                userId,
                Instant.now().minusSeconds(900),
                Instant.now(),
                AttemptStatus.COMPLETED,
                AttemptMode.ALL_AT_ONCE,
                8.0,
                quizSummary,
                stats
        );

        Page<AttemptSummaryDto> page = new PageImpl<>(List.of(summary));
        when(attemptService.getAttemptsSummary(
                eq("testuser"),
                any(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/summary")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.content[0].quiz").exists())
                .andExpect(jsonPath("$.content[0].quiz.id").value(quizId.toString()))
                .andExpect(jsonPath("$.content[0].quiz.title").value("Java Fundamentals Quiz"))
                .andExpect(jsonPath("$.content[0].quiz.questionCount").value(10))
                .andExpect(jsonPath("$.content[0].stats").exists())
                .andExpect(jsonPath("$.content[0].stats.accuracyPercentage").value(80.0))
                .andExpect(jsonPath("$.content[0].stats.completionPercentage").value(100.0));
    }

    @Test
    @DisplayName("GET /api/v1/attempts/summary: with filters then applies filters")
    @WithMockUser(username = "testuser")
    void getAttemptsSummary_withFilters_appliesFilters() throws Exception {
        // Given
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String statusParam = "COMPLETED";

        Page<AttemptSummaryDto> emptyPage = new PageImpl<>(List.of());
        when(attemptService.getAttemptsSummary(
                eq("testuser"),
                any(),
                eq(quizId),
                eq(userId),
                eq(AttemptStatus.COMPLETED)
        )).thenReturn(emptyPage);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/summary")
                        .param("quizId", quizId.toString())
                        .param("userId", userId.toString())
                        .param("status", statusParam)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/attempts/summary: with pagination then respects page params")
    @WithMockUser(username = "testuser")
    void getAttemptsSummary_withPagination_respectsParams() throws Exception {
        // Given
        Page<AttemptSummaryDto> emptyPage = new PageImpl<>(List.of());
        when(attemptService.getAttemptsSummary(
                eq("testuser"),
                any(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn(emptyPage);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/summary")
                        .param("page", "2")
                        .param("size", "50")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/summary: without authentication then returns 401")
    void getAttemptsSummary_noAuth_returns401() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/attempts/summary")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/summary: in-progress attempts have null stats")
    @WithMockUser(username = "testuser")
    void getAttemptsSummary_inProgressAttempt_nullStats() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();

        QuizSummaryDto quizSummary = new QuizSummaryDto(
                quizId,
                "Test Quiz",
                10,
                UUID.randomUUID(),
                true
        );

        AttemptSummaryDto summary = new AttemptSummaryDto(
                attemptId,
                quizId,
                UUID.randomUUID(),
                Instant.now().minusSeconds(300),
                null,  // Not completed
                AttemptStatus.IN_PROGRESS,
                AttemptMode.ALL_AT_ONCE,
                null,  // No score yet
                quizSummary,
                null  // No stats for in-progress
        );

        Page<AttemptSummaryDto> page = new PageImpl<>(List.of(summary));
        when(attemptService.getAttemptsSummary(
                eq("testuser"),
                any(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/summary")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].stats").doesNotExist());  // Null stats excluded
    }
}
