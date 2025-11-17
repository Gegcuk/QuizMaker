package uk.gegc.quizmaker.features.admin.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.admin.aplication.PermissionService;
import uk.gegc.quizmaker.features.admin.aplication.RoleService;
import uk.gegc.quizmaker.features.admin.application.PolicyReconciliationService;
import uk.gegc.quizmaker.features.admin.api.dto.RoleDto;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.email.EmailService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.PermissionUtil;
import uk.gegc.quizmaker.shared.security.aspect.PermissionAspect;
import uk.gegc.quizmaker.shared.util.XssSanitizer;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@Import(PermissionAspect.class)
@EnableAspectJAutoProxy
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleService roleService;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private PermissionUtil permissionUtil;

    @MockitoBean
    private AppPermissionEvaluator appPermissionEvaluator;

    @MockitoBean
    private PolicyReconciliationService policyReconciliationService;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private XssSanitizer xssSanitizer;

    @Test
    @DisplayName("GET /api/v1/admin/roles/paginated: when called then returns paginated roles")
    @WithMockUser(authorities = "ROLE_READ")
    void getAllRolesPaginated_whenCalled_thenReturnsPaginatedRoles() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_READ)).thenReturn(true);
        RoleDto role1 = new RoleDto(
                1L,
                "ROLE_USER",
                "Basic user role",
                true,
                Set.of(),
                0
        );

        RoleDto role2 = new RoleDto(
                2L,
                "ROLE_ADMIN",
                "Admin role",
                false,
                Set.of(),
                0
        );

        Page<RoleDto> rolesPage = new PageImpl<>(List.of(role1, role2), PageRequest.of(0, 20), 2);
        
        when(roleService.getAllRoles(any(Pageable.class), any())).thenReturn(rolesPage);

        // When & Then
        mockMvc.perform(get("/api/v1/admin/roles/paginated")
                        .param("page", "0")
                        .param("size", "20")
                        .param("search", "user")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].roleName").value("ROLE_USER"))
                .andExpect(jsonPath("$.content[1].roleName").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/admin/roles/paginated: when no search provided then returns all roles")
    @WithMockUser(authorities = "ROLE_READ")
    void getAllRolesPaginated_whenNoSearch_thenReturnsAllRoles() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_READ)).thenReturn(true);
        RoleDto role = new RoleDto(
                1L,
                "ROLE_USER",
                "Basic user role",
                true,
                Set.of(),
                0
        );

        Page<RoleDto> rolesPage = new PageImpl<>(List.of(role), PageRequest.of(0, 20), 1);
        
        when(roleService.getAllRoles(any(Pageable.class), any())).thenReturn(rolesPage);

        // When & Then
        mockMvc.perform(get("/api/v1/admin/roles/paginated")
                        .param("page", "0")
                        .param("size", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].roleName").value("ROLE_USER"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/email/test-verification: when email contains XSS then sanitizes in response")
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    void testEmailVerification_whenEmailContainsXss_thenSanitizesInResponse() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        String maliciousEmail = "test<script>alert('xss')</script>@example.com";
        String sanitizedEmail = "test@example.com";
        when(xssSanitizer.sanitize(maliciousEmail)).thenReturn(sanitizedEmail);
        doNothing().when(emailService).sendEmailVerificationEmail(anyString(), anyString());

        // When & Then
        mockMvc.perform(post("/api/v1/admin/email/test-verification")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("email", maliciousEmail))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(sanitizedEmail)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alert('xss')"))));
        
        verify(xssSanitizer).sanitize(maliciousEmail);
    }

    @Test
    @DisplayName("POST /api/v1/admin/email/test-verification: when exception contains XSS then sanitizes in error response")
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    void testEmailVerification_whenExceptionContainsXss_thenSanitizesInErrorResponse() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        String email = "test@example.com";
        String maliciousExceptionMessage = "<script>alert('xss')</script>Email service error";
        String sanitizedExceptionMessage = "Email service error";
        when(xssSanitizer.sanitize(email)).thenReturn(email);
        when(xssSanitizer.sanitize(maliciousExceptionMessage)).thenReturn(sanitizedExceptionMessage);
        when(xssSanitizer.sanitize(anyString())).thenAnswer(invocation -> {
            String input = invocation.getArgument(0);
            if (input == null) return "Unknown error";
            return input.replaceAll("<script[^>]*>.*?</script>", "")
                    .replaceAll("<[^>]+>", "")
                    .trim();
        });
        doThrow(new RuntimeException(maliciousExceptionMessage))
                .when(emailService).sendEmailVerificationEmail(anyString(), anyString());

        // When & Then
        mockMvc.perform(post("/api/v1/admin/email/test-verification")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("email", email))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(sanitizedExceptionMessage)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alert('xss')"))));
        
        // Verify exception message is sanitized (email is not sanitized in exception case)
        verify(xssSanitizer).sanitize(maliciousExceptionMessage);
    }

    @Test
    @DisplayName("POST /api/v1/admin/email/test-password-reset: when email contains XSS then sanitizes in response")
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    void testPasswordResetEmail_whenEmailContainsXss_thenSanitizesInResponse() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        String maliciousEmail = "test<script>alert('xss')</script>@example.com";
        String sanitizedEmail = "test@example.com";
        when(xssSanitizer.sanitize(maliciousEmail)).thenReturn(sanitizedEmail);
        doNothing().when(emailService).sendPasswordResetEmail(anyString(), anyString());

        // When & Then
        mockMvc.perform(post("/api/v1/admin/email/test-password-reset")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("email", maliciousEmail))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(sanitizedEmail)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alert('xss')"))));
        
        verify(xssSanitizer).sanitize(maliciousEmail);
    }

    @Test
    @DisplayName("POST /api/v1/admin/email/test-password-reset: when exception contains XSS then sanitizes in error response")
    @WithMockUser(authorities = "SYSTEM_ADMIN")
    void testPasswordResetEmail_whenExceptionContainsXss_thenSanitizesInErrorResponse() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        String email = "test@example.com";
        String maliciousExceptionMessage = "<script>alert('xss')</script>Email service error";
        String sanitizedExceptionMessage = "Email service error";
        when(xssSanitizer.sanitize(email)).thenReturn(email);
        when(xssSanitizer.sanitize(maliciousExceptionMessage)).thenReturn(sanitizedExceptionMessage);
        when(xssSanitizer.sanitize(anyString())).thenAnswer(invocation -> {
            String input = invocation.getArgument(0);
            if (input == null) return "Unknown error";
            return input.replaceAll("<script[^>]*>.*?</script>", "")
                    .replaceAll("<[^>]+>", "")
                    .trim();
        });
        doThrow(new RuntimeException(maliciousExceptionMessage))
                .when(emailService).sendPasswordResetEmail(anyString(), anyString());

        // When & Then
        mockMvc.perform(post("/api/v1/admin/email/test-password-reset")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("email", email))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(sanitizedExceptionMessage)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alert('xss')"))));
        
        // Verify exception message is sanitized (email is not sanitized in exception case)
        verify(xssSanitizer).sanitize(maliciousExceptionMessage);
    }
}
