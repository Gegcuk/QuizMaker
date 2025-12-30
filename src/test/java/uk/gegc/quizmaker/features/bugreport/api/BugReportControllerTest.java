package uk.gegc.quizmaker.features.bugreport.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.bugreport.api.dto.BugReportDto;
import uk.gegc.quizmaker.features.bugreport.api.dto.CreateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.application.BugReportService;
import uk.gegc.quizmaker.features.bugreport.config.BugReportProperties;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(BugReportController.class)
class BugReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BugReportService bugReportService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @MockitoBean
    private BugReportProperties bugReportProperties;

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/bug-reports returns 201 with created id")
    void submitBugReport_success() throws Exception {
        UUID id = UUID.randomUUID();
        BugReportDto dto = new BugReportDto(
                id,
                "Editor crashed",
                "Jane Doe",
                "jane@example.com",
                "https://app.local",
                null,
                null,
                "127.0.0.1",
                BugReportSeverity.UNSPECIFIED,
                BugReportStatus.OPEN,
                null,
                Instant.now(),
                Instant.now()
        );

        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");
        when(bugReportProperties.getRateLimitPerMinute()).thenReturn(5);
        when(bugReportService.createReport(any(CreateBugReportRequest.class), eq("127.0.0.1")))
                .thenReturn(dto);

        CreateBugReportRequest request = new CreateBugReportRequest(
                "Editor crashed while saving",
                "Jane Doe",
                "jane@example.com",
                "https://app.local",
                "Open quiz -> click save",
                "web 1.0",
                BugReportSeverity.HIGH
        );

        mockMvc.perform(post("/api/v1/bug-reports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bugReportId").value(id.toString()));

        verify(rateLimitService).checkRateLimit("bug-report-submit", "127.0.0.1", 5);
        verify(bugReportService).createReport(any(CreateBugReportRequest.class), eq("127.0.0.1"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/v1/bug-reports without message returns 400")
    void submitBugReport_missingMessage() throws Exception {
        when(bugReportProperties.getRateLimitPerMinute()).thenReturn(5);
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        mockMvc.perform(post("/api/v1/bug-reports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bugReportService);
    }
}
