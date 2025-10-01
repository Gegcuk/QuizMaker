package uk.gegc.quizmaker.features.admin.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
import uk.gegc.quizmaker.shared.email.EmailService;
import uk.gegc.quizmaker.shared.security.PermissionUtil;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
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
    private PolicyReconciliationService policyReconciliationService;

    @MockitoBean
    private EmailService emailService;

    @Test
    @DisplayName("GET /api/v1/admin/roles/paginated: when called then returns paginated roles")
    @WithMockUser(authorities = "ROLE_READ")
    void getAllRolesPaginated_whenCalled_thenReturnsPaginatedRoles() throws Exception {
        // Given
        RoleDto role1 = RoleDto.builder()
                .roleId(1L)
                .roleName("ROLE_USER")
                .description("Basic user role")
                .isDefault(true)
                .permissions(Set.of())
                .build();

        RoleDto role2 = RoleDto.builder()
                .roleId(2L)
                .roleName("ROLE_ADMIN")
                .description("Admin role")
                .isDefault(false)
                .permissions(Set.of())
                .build();

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
        RoleDto role = RoleDto.builder()
                .roleId(1L)
                .roleName("ROLE_USER")
                .description("Basic user role")
                .isDefault(true)
                .permissions(Set.of())
                .build();

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
}
