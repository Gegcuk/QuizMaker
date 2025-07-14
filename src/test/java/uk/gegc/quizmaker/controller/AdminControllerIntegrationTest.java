package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.admin.CreateRoleRequest;
import uk.gegc.quizmaker.dto.admin.RoleDto;
import uk.gegc.quizmaker.dto.admin.UpdateRoleRequest;
import uk.gegc.quizmaker.model.user.*;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.security.PermissionEvaluator;
import uk.gegc.quizmaker.service.admin.RoleService;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @SuppressWarnings("deprecation")
    @MockBean
    private RoleService roleService;

    @SuppressWarnings("deprecation")
    @MockBean
    private UserRepository userRepository;

    @SuppressWarnings("deprecation")
    @MockBean
    private PermissionEvaluator permissionEvaluator;

    @SuppressWarnings("deprecation")
    @MockBean
    private uk.gegc.quizmaker.security.PermissionUtil permissionUtil;

    private User testUser;
    private User adminUser;
    private User superAdminUser;
    
    @BeforeEach
    void setUp() {
        // Set up test user with basic permissions
        Permission readPermission = Permission.builder()
                .permissionId(1L)
                .permissionName(PermissionName.ROLE_READ.name())
                .build();
        
        Role userRole = Role.builder()
                .roleId(1L)
                .roleName(RoleName.ROLE_USER.name())
                .permissions(Set.of(readPermission))
                .build();
        
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");
        testUser.setRoles(Set.of(userRole));
        
        // Set up admin user with admin permissions
        Permission roleCreatePermission = Permission.builder()
                .permissionId(2L)
                .permissionName(PermissionName.ROLE_CREATE.name())
                .build();
        
        Permission roleUpdatePermission = Permission.builder()
                .permissionId(3L)
                .permissionName(PermissionName.ROLE_UPDATE.name())
                .build();
        
        Permission roleDeletePermission = Permission.builder()
                .permissionId(4L)
                .permissionName(PermissionName.ROLE_DELETE.name())
                .build();
        
        Role adminRole = Role.builder()
                .roleId(2L)
                .roleName(RoleName.ROLE_ADMIN.name())
                .permissions(Set.of(readPermission, roleCreatePermission, roleUpdatePermission, roleDeletePermission))
                .build();
        
        adminUser = new User();
        adminUser.setId(UUID.randomUUID());
        adminUser.setUsername("admin");
        adminUser.setRoles(Set.of(adminRole));
        
        // Set up super admin user
        Permission systemAdminPermission = Permission.builder()
                .permissionId(5L)
                .permissionName(PermissionName.SYSTEM_ADMIN.name())
                .build();
        
        Role superAdminRole = Role.builder()
                .roleId(3L)
                .roleName(RoleName.ROLE_SUPER_ADMIN.name())
                .permissions(Set.of(readPermission, roleCreatePermission, roleUpdatePermission, 
                        roleDeletePermission, systemAdminPermission))
                .build();
        
        superAdminUser = new User();
        superAdminUser.setId(UUID.randomUUID());
        superAdminUser.setUsername("superadmin");
        superAdminUser.setRoles(Set.of(superAdminRole));
    }

    @Test
    @DisplayName("GET /api/v1/admin/roles - authenticated user with ROLE_READ permission can get all roles")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void getAllRoles_withPermission_success() throws Exception {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(permissionEvaluator.hasAnyPermission(PermissionName.ROLE_READ)).thenReturn(true);
        
        List<RoleDto> roles = Arrays.asList(
                RoleDto.builder().roleId(1L).roleName("ROLE_USER").build(),
                RoleDto.builder().roleId(2L).roleName("ROLE_ADMIN").build()
        );
        when(roleService.getAllRoles()).thenReturn(roles);
        
        // When & Then
        mockMvc.perform(get("/api/v1/admin/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roleName").value("ROLE_USER"))
                .andExpect(jsonPath("$[1].roleName").value("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/roles - unauthenticated user gets 403")
    void getAllRoles_unauthenticated_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/roles"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/admin/roles - user with ROLE_CREATE permission can create role")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void createRole_withPermission_success() throws Exception {
        // Given
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(permissionEvaluator.hasAnyPermission(PermissionName.ROLE_CREATE)).thenReturn(true);
        
        CreateRoleRequest request = CreateRoleRequest.builder()
                .roleName("ROLE_TEST")
                .description("Test role")
                .isDefault(false)
                .build();
        
        RoleDto createdRole = RoleDto.builder()
                .roleId(4L)
                .roleName("ROLE_TEST")
                .description("Test role")
                .isDefault(false)
                .build();
        
        when(roleService.createRole(any(CreateRoleRequest.class))).thenReturn(createdRole);
        
        // When & Then
        mockMvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleName").value("ROLE_TEST"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/roles - user without ROLE_CREATE permission gets 403")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void createRole_withoutPermission_forbidden() throws Exception {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(permissionEvaluator.hasAnyPermission(PermissionName.ROLE_CREATE)).thenReturn(false);
        
        CreateRoleRequest request = CreateRoleRequest.builder()
                .roleName("ROLE_TEST")
                .build();
        
        // When & Then
        mockMvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/v1/admin/roles/{roleId} - user with ROLE_UPDATE permission can update role")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void updateRole_withPermission_success() throws Exception {
        // Given
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(permissionEvaluator.hasAnyPermission(PermissionName.ROLE_UPDATE)).thenReturn(true);
        
        Long roleId = 1L;
        UpdateRoleRequest request = UpdateRoleRequest.builder()
                .description("Updated description")
                .isDefault(true)
                .build();
        
        RoleDto updatedRole = RoleDto.builder()
                .roleId(roleId)
                .roleName("ROLE_USER")
                .description("Updated description")
                .isDefault(true)
                .build();
        
        when(roleService.updateRole(eq(roleId), any(UpdateRoleRequest.class))).thenReturn(updatedRole);
        
        // When & Then
        mockMvc.perform(put("/api/v1/admin/roles/{roleId}", roleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated description"));
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/roles/{roleId} - user with ROLE_DELETE permission can delete role")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void deleteRole_withPermission_success() throws Exception {
        // Given
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(permissionEvaluator.hasAnyPermission(PermissionName.ROLE_DELETE)).thenReturn(true);
        
        Long roleId = 1L;
        
        // When & Then
        mockMvc.perform(delete("/api/v1/admin/roles/{roleId}", roleId))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /api/v1/admin/users/{userId}/roles/{roleId} - only ADMIN role can assign roles")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void assignRoleToUser_asAdmin_success() throws Exception {
        // Given
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(permissionEvaluator.hasAnyRole(RoleName.ROLE_ADMIN)).thenReturn(true);
        
        UUID userId = UUID.randomUUID();
        Long roleId = 1L;
        
        // When & Then
        mockMvc.perform(post("/api/v1/admin/users/{userId}/roles/{roleId}", userId, roleId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/admin/users/{userId}/roles/{roleId} - non-admin gets 403")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void assignRoleToUser_notAdmin_forbidden() throws Exception {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(permissionEvaluator.hasAnyRole(RoleName.ROLE_ADMIN)).thenReturn(false);
        
        UUID userId = UUID.randomUUID();
        Long roleId = 1L;
        
        // When & Then
        mockMvc.perform(post("/api/v1/admin/users/{userId}/roles/{roleId}", userId, roleId))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/admin/system/initialize - only SYSTEM_ADMIN permission allows initialization")
    @WithMockUser(username = "superadmin", roles = {"SUPER_ADMIN"})
    void initializeSystem_withSystemAdmin_success() throws Exception {
        // Given
        when(userRepository.findByUsername("superadmin")).thenReturn(Optional.of(superAdminUser));
        when(permissionEvaluator.getCurrentUser()).thenReturn(superAdminUser);
        when(permissionEvaluator.hasPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(true);
        
        // When & Then
        mockMvc.perform(post("/api/v1/admin/system/initialize"))
                .andExpect(status().isOk())
                .andExpect(content().string("System initialized successfully"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/system/initialize - regular admin gets 403")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void initializeSystem_withoutSystemAdmin_forbidden() throws Exception {
        // Given
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(permissionEvaluator.getCurrentUser()).thenReturn(adminUser);
        when(permissionEvaluator.hasPermission(PermissionName.SYSTEM_ADMIN)).thenReturn(false);
        
        // Mock the PermissionUtil to throw ForbiddenException when requirePermission is called
        doThrow(new uk.gegc.quizmaker.exception.ForbiddenException("Insufficient permissions to access this resource"))
                .when(permissionUtil).requirePermission(PermissionName.SYSTEM_ADMIN);
        
        // When & Then
        mockMvc.perform(post("/api/v1/admin/system/initialize"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/admin/system/status - user with SYSTEM_ADMIN or AUDIT_READ can view status")
    @WithMockUser(username = "superadmin", roles = {"SUPER_ADMIN"})
    void getSystemStatus_withPermission_success() throws Exception {
        // Given
        when(userRepository.findByUsername("superadmin")).thenReturn(Optional.of(superAdminUser));
        when(permissionEvaluator.hasAnyPermission(PermissionName.SYSTEM_ADMIN, PermissionName.AUDIT_READ)).thenReturn(true);
        when(permissionEvaluator.isSuperAdmin()).thenReturn(true);
        when(permissionUtil.isSuperAdmin()).thenReturn(true);
        
        // When & Then
        mockMvc.perform(get("/api/v1/admin/system/status"))
                .andExpect(status().isOk())
                .andExpect(content().string("System status: All systems operational (Super Admin view)"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/super/dangerous-operation - only SUPER_ADMIN role allowed")
    @WithMockUser(username = "superadmin", roles = {"SUPER_ADMIN"})
    void performDangerousOperation_asSuperAdmin_success() throws Exception {
        // Given
        when(userRepository.findByUsername("superadmin")).thenReturn(Optional.of(superAdminUser));
        when(permissionEvaluator.hasAnyRole(RoleName.ROLE_SUPER_ADMIN)).thenReturn(true);
        when(permissionEvaluator.getCurrentUser()).thenReturn(superAdminUser);
        when(permissionUtil.getCurrentUser()).thenReturn(superAdminUser);
        
        // When & Then
        mockMvc.perform(post("/api/v1/admin/super/dangerous-operation"))
                .andExpect(status().isOk())
                .andExpect(content().string("Operation completed"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/super/dangerous-operation - regular admin gets 403")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void performDangerousOperation_notSuperAdmin_forbidden() throws Exception {
        // Given
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(permissionEvaluator.hasAnyRole(RoleName.ROLE_SUPER_ADMIN)).thenReturn(false);
        
        // When & Then
        mockMvc.perform(post("/api/v1/admin/super/dangerous-operation"))
                .andExpect(status().isForbidden());
    }
} 