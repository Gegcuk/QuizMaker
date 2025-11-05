package uk.gegc.quizmaker.features.admin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.admin.api.dto.CreateRoleRequest;
import uk.gegc.quizmaker.features.admin.api.dto.RoleDto;
import uk.gegc.quizmaker.features.admin.api.dto.UpdateRoleRequest;
import uk.gegc.quizmaker.features.admin.aplication.RoleService;
import uk.gegc.quizmaker.features.admin.application.PolicyReconciliationService;
import uk.gegc.quizmaker.features.user.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.PermissionUtil;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RoleService roleService;

    @SuppressWarnings("deprecation")
    @MockitoBean
    private AppPermissionEvaluator appPermissionEvaluator;

    @MockitoBean
    private PermissionUtil permissionUtil;

    @MockitoBean
    private PolicyReconciliationService policyReconciliationService;

    @Autowired
    private UserRepository userRepository;

    private User superAdminUser;
    private User regularUser;

    @BeforeEach
    void setUp() {
        // Create test users
        superAdminUser = new User();
        superAdminUser.setUsername("superadmin");
        superAdminUser.setEmail("superadmin@test.com");
        superAdminUser.setHashedPassword("password");
        superAdminUser.setEmailVerified(true);
        superAdminUser = userRepository.save(superAdminUser);

        regularUser = new User();
        regularUser.setUsername("user");
        regularUser.setEmail("user@test.com");
        regularUser.setHashedPassword("password");
        regularUser.setEmailVerified(true);
        regularUser = userRepository.save(regularUser);

        // Setup common mocks
        when(permissionUtil.getCurrentUser()).thenReturn(superAdminUser);
    }

    @Test
    @DisplayName("GET /api/v1/admin/system/status: when authorized then returns system status")
    @WithMockUser(username = "superadmin")
    void getSystemStatus_whenAuthorized_thenReturnsSystemStatus() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN, PermissionName.AUDIT_READ)).thenReturn(true);

        // When & Then
        mockMvc.perform(get("/api/v1/admin/system/status"))
                .andExpect(status().isOk())
                .andExpect(content().string("System status: All systems operational"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/system/status: when unauthorized then returns 403")
    @WithMockUser(username = "user")
    void getSystemStatus_whenUnauthorized_thenReturns403() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN, PermissionName.AUDIT_READ)).thenReturn(false);

        // When & Then
        mockMvc.perform(get("/api/v1/admin/system/status"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/admin/system/initialize: when authorized then initializes system")
    @WithMockUser(username = "superadmin")
    void initializeSystem_whenAuthorized_thenInitializesSystem() throws Exception {
        // Given
        when(appPermissionEvaluator.hasPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/system/initialize"))
                .andExpect(status().isOk())
                .andExpect(content().string("System initialized successfully"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/system/initialize: when unauthorized then returns 403")
    @WithMockUser(username = "user")
    void initializeSystem_whenUnauthorized_thenReturns403() throws Exception {
        // Given
        when(appPermissionEvaluator.hasPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(false);
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/system/initialize"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/admin/roles: when authorized then creates role")
    @WithMockUser(username = "superadmin")
    void createRole_whenAuthorized_thenCreatesRole() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_CREATE)).thenReturn(true);
        
        CreateRoleRequest request = new CreateRoleRequest(
                "TEST_ROLE",
                "Test role",
                true
        );
        RoleDto expectedRole = new RoleDto(
                1L,
                "TEST_ROLE",
                "Test role",
                true,
                Set.of("QUIZ_READ"),
                0
        );
        
        when(roleService.createRole(any(CreateRoleRequest.class))).thenReturn(expectedRole);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleName").value("TEST_ROLE"))
                .andExpect(jsonPath("$.description").value("Test role"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/roles: when unauthorized then returns 403")
    @WithMockUser(username = "user")
    void createRole_whenUnauthorized_thenReturns403() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_CREATE)).thenReturn(false);
        
        CreateRoleRequest request = new CreateRoleRequest(
                "TEST_ROLE",
                "Test role",
                true
        );

        // When & Then
        mockMvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/v1/admin/roles/{id}: when authorized then updates role")
    @WithMockUser(username = "superadmin")
    void updateRole_whenAuthorized_thenUpdatesRole() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_UPDATE)).thenReturn(true);
        
        UpdateRoleRequest request = new UpdateRoleRequest(
                "Updated role",
                false
        );
        RoleDto expectedRole = new RoleDto(
                1L,
                "UPDATED_ROLE",
                "Updated role",
                false,
                Set.of("QUIZ_READ", "QUIZ_WRITE"),
                0
        );
        
        when(roleService.updateRole(eq(1L), any(UpdateRoleRequest.class))).thenReturn(expectedRole);

        // When & Then
        mockMvc.perform(put("/api/v1/admin/roles/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleName").value("UPDATED_ROLE"))
                .andExpect(jsonPath("$.description").value("Updated role"));
    }

    @Test
    @DisplayName("PUT /api/v1/admin/roles/{id}: when unauthorized then returns 403")
    @WithMockUser(username = "user")
    void updateRole_whenUnauthorized_thenReturns403() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_UPDATE)).thenReturn(false);
        
        UpdateRoleRequest request = new UpdateRoleRequest(
                "Updated role",
                false
        );

        // When & Then
        mockMvc.perform(put("/api/v1/admin/roles/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/roles/{id}: when authorized then deletes role")
    @WithMockUser(username = "superadmin")
    void deleteRole_whenAuthorized_thenDeletesRole() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_DELETE)).thenReturn(true);

        // When & Then
        mockMvc.perform(delete("/api/v1/admin/roles/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/roles/{id}: when unauthorized then returns 403")
    @WithMockUser(username = "user")
    void deleteRole_whenUnauthorized_thenReturns403() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_DELETE)).thenReturn(false);

        // When & Then
        mockMvc.perform(delete("/api/v1/admin/roles/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/admin/users/{userId}/roles/{roleId}: when authorized then assigns role")
    @WithMockUser(username = "superadmin")
    void assignRoleToUser_whenAuthorized_thenAssignsRole() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_ASSIGN)).thenReturn(true);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/users/{userId}/roles/{roleId}", superAdminUser.getId(), 1L))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/admin/users/{userId}/roles/{roleId}: when unauthorized then returns 403")
    @WithMockUser(username = "user")
    void assignRoleToUser_whenUnauthorized_thenReturns403() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_ASSIGN)).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/users/{userId}/roles/{roleId}", regularUser.getId(), 1L))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/users/{userId}/roles/{roleId}: when authorized then removes role")
    @WithMockUser(username = "superadmin")
    void removeRoleFromUser_whenAuthorized_thenRemovesRole() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_ASSIGN)).thenReturn(true);

        // When & Then
        mockMvc.perform(delete("/api/v1/admin/users/{userId}/roles/{roleId}", superAdminUser.getId(), 1L))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/users/{userId}/roles/{roleId}: when unauthorized then returns 403")
    @WithMockUser(username = "user")
    void removeRoleFromUser_whenUnauthorized_thenReturns403() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.ROLE_ASSIGN)).thenReturn(false);

        // When & Then
        mockMvc.perform(delete("/api/v1/admin/users/{userId}/roles/{roleId}", regularUser.getId(), 1L))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/admin/super/dangerous-operation: when authorized then performs operation")
    @WithMockUser(username = "superadmin")
    void performDangerousOperation_whenAuthorized_thenPerformsOperation() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/super/dangerous-operation"))
                .andExpect(status().isOk())
                .andExpect(content().string("Operation completed"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/super/dangerous-operation: when unauthorized then returns 403")
    @WithMockUser(username = "user")
    void performDangerousOperation_whenUnauthorized_thenReturns403() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/super/dangerous-operation"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/admin/policy/reconcile: when authorized then reconciles policy")
    @WithMockUser(username = "superadmin")
    void reconcilePolicy_whenAuthorized_thenReconcilesPolicy() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        
        PolicyReconciliationService.ReconciliationResult result = new PolicyReconciliationService.ReconciliationResult(
                true, "Policy reconciliation completed successfully", 0, 0, 0, 0, 0, List.of()
        );
        when(policyReconciliationService.reconcileAll()).thenReturn(result);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/policy/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Policy reconciliation completed successfully"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/policy/status: when authorized then returns policy status")
    @WithMockUser(username = "superadmin")
    void getPolicyStatus_whenAuthorized_thenReturnsPolicyStatus() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        
        PolicyReconciliationService.PolicyDiff diff = new PolicyReconciliationService.PolicyDiff(
                List.of(), List.of(), List.of(), List.of(), Map.of(), "1.0.0", true
        );
        when(policyReconciliationService.getPolicyDiff()).thenReturn(diff);

        // When & Then
        mockMvc.perform(get("/api/v1/admin/policy/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isInSync").value(true))
                .andExpect(jsonPath("$.manifestVersion").value("1.0.0"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/policy/version: when authorized then returns manifest version")
    @WithMockUser(username = "superadmin")
    void getManifestVersion_whenAuthorized_thenReturnsManifestVersion() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        when(policyReconciliationService.getManifestVersion()).thenReturn("1.0.0");

        // When & Then
        mockMvc.perform(get("/api/v1/admin/policy/version"))
                .andExpect(status().isOk())
                .andExpect(content().string("1.0.0"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/policy/reconcile/{roleName}: when authorized then reconciles specific role")
    @WithMockUser(username = "superadmin")
    void reconcileSpecificRole_whenAuthorized_thenReconcilesRole() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        
        PolicyReconciliationService.ReconciliationResult result = new PolicyReconciliationService.ReconciliationResult(
                true, "Role reconciliation completed", 0, 0, 1, 0, 0, List.of()
        );
        when(policyReconciliationService.reconcileRole("ROLE_ADMIN")).thenReturn(result);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/policy/reconcile/ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Role reconciliation completed"))
                .andExpect(jsonPath("$.rolesAdded").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/admin/policy/reconcile/{roleName}: when role not found then returns error")
    @WithMockUser(username = "superadmin")
    void reconcileSpecificRole_whenRoleNotFound_thenReturnsError() throws Exception {
        // Given
        when(appPermissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        
        PolicyReconciliationService.ReconciliationResult result = new PolicyReconciliationService.ReconciliationResult(
                false, "Role reconciliation failed", 0, 0, 0, 0, 0, List.of("Role not found in manifest: INVALID_ROLE")
        );
        when(policyReconciliationService.reconcileRole("INVALID_ROLE")).thenReturn(result);

        // When & Then
        mockMvc.perform(post("/api/v1/admin/policy/reconcile/INVALID_ROLE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Role reconciliation failed"))
                .andExpect(jsonPath("$.errors[0]").value("Role not found in manifest: INVALID_ROLE"));
    }
}
