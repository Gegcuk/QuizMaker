package uk.gegc.quizmaker.features.bugreport.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.bugreport.api.dto.BugReportDto;
import uk.gegc.quizmaker.features.bugreport.api.dto.CreateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.api.dto.UpdateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.application.BugReportService;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.aspect.PermissionAspect;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(BugReportAdminController.class)
@Import(PermissionAspect.class)
@EnableAspectJAutoProxy
class BugReportAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BugReportService bugReportService;

    @MockitoBean
    private AppPermissionEvaluator appPermissionEvaluator;

    private BugReportDto sampleDto;

    @BeforeEach
    void setUp() {
        when(appPermissionEvaluator.hasAnyPermission(any(PermissionName[].class))).thenReturn(true);
        when(appPermissionEvaluator.hasAllPermissions(any(PermissionName[].class))).thenReturn(true);

        sampleDto = new BugReportDto(
                UUID.randomUUID(),
                "Editor crashed",
                "Jane",
                "jane@example.com",
                "https://app.local",
                "1) Open editor",
                "web 1.0",
                "127.0.0.1",
                BugReportSeverity.UNSPECIFIED,
                BugReportStatus.OPEN,
                null,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("GET /api/v1/admin/bug-reports returns paginated reports")
    void listBugReports_success() throws Exception {
        Page<BugReportDto> page = new PageImpl<>(List.of(sampleDto), PageRequest.of(0, 10), 1);
        when(bugReportService.listReports(any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/bug-reports")
                        .param("status", "OPEN")
                        .param("severity", "UNSPECIFIED")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(sampleDto.id().toString()))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("PATCH /api/v1/admin/bug-reports/{id} updates report")
    void updateBugReport_success() throws Exception {
        UUID id = UUID.randomUUID();
        BugReportDto updated = new BugReportDto(
                id,
                "Updated",
                "Jane",
                "jane@example.com",
                null,
                null,
                null,
                "127.0.0.1",
                BugReportSeverity.LOW,
                BugReportStatus.RESOLVED,
                "fixed",
                Instant.now(),
                Instant.now()
        );

        when(bugReportService.updateReport(eq(id), any(UpdateBugReportRequest.class))).thenReturn(updated);

        UpdateBugReportRequest request = new UpdateBugReportRequest(
                "Updated",
                null,
                null,
                null,
                null,
                null,
                BugReportSeverity.LOW,
                BugReportStatus.RESOLVED,
                "fixed"
        );

        mockMvc.perform(patch("/api/v1/admin/bug-reports/{id}", id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.severity").value("LOW"));
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("PATCH /api/v1/admin/bug-reports/{id} with blank message returns 400")
    void updateBugReport_blankMessage() throws Exception {
        UpdateBugReportRequest request = new UpdateBugReportRequest(
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(patch("/api/v1/admin/bug-reports/{id}", UUID.randomUUID()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("POST /api/v1/admin/bug-reports creates a bug report")
    void createBugReport_success() throws Exception {
        when(bugReportService.createReport(any(CreateBugReportRequest.class), isNull()))
                .thenReturn(sampleDto);

        CreateBugReportRequest request = new CreateBugReportRequest(
                "Something broke",
                "Jane",
                "jane@example.com",
                null,
                null,
                null,
                BugReportSeverity.HIGH
        );

        mockMvc.perform(post("/api/v1/admin/bug-reports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sampleDto.id().toString()));
    }

    @Test
    @WithMockUser(authorities = "OTHER")
    @DisplayName("POST /api/v1/admin/bug-reports without SYSTEM_ADMIN returns 403")
    void createBugReport_forbiddenWithoutPermission() throws Exception {
        when(appPermissionEvaluator.hasAnyPermission(any(PermissionName[].class))).thenReturn(false);

        CreateBugReportRequest request = new CreateBugReportRequest(
                "Something broke",
                "Jane",
                "jane@example.com",
                null,
                null,
                null,
                BugReportSeverity.HIGH
        );

        mockMvc.perform(post("/api/v1/admin/bug-reports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("POST /api/v1/admin/bug-reports/bulk-delete deletes provided ids")
    void bulkDeleteBugReports_success() throws Exception {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/api/v1/admin/bug-reports/bulk-delete").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ids)))
                .andExpect(status().isNoContent());

        verify(bugReportService).deleteReports(ids);
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("GET /api/v1/admin/bug-reports/{id} returns 404 when not found")
    void getBugReport_notFound() throws Exception {
        when(bugReportService.getReport(sampleDto.id())).thenThrow(new ResourceNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/admin/bug-reports/{id}", sampleDto.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("GET /api/v1/admin/bug-reports with invalid status enum returns 400")
    void listBugReports_invalidStatus() throws Exception {
        mockMvc.perform(get("/api/v1/admin/bug-reports").param("status", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("GET /api/v1/admin/bug-reports/{id} returns report")
    void getBugReport_success() throws Exception {
        when(bugReportService.getReport(sampleDto.id())).thenReturn(sampleDto);

        mockMvc.perform(get("/api/v1/admin/bug-reports/{id}", sampleDto.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sampleDto.id().toString()))
                .andExpect(jsonPath("$.message").value("Editor crashed"));
    }

    @Test
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    @DisplayName("POST /api/v1/admin/bug-reports rejects missing message")
    void createBugReport_validationFailure() throws Exception {
        CreateBugReportRequest request = new CreateBugReportRequest(
                null,
                "Jane",
                "jane@example.com",
                null,
                null,
                null,
                BugReportSeverity.HIGH
        );

        mockMvc.perform(post("/api/v1/admin/bug-reports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bugReportService);
    }
}
