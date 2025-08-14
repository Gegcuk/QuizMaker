package uk.gegc.quizmaker.service.admin;

import uk.gegc.quizmaker.dto.admin.CreateRoleRequest;
import uk.gegc.quizmaker.dto.admin.RoleDto;
import uk.gegc.quizmaker.dto.admin.UpdateRoleRequest;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RoleService {

    /**
     * Create a new role
     */
    RoleDto createRole(CreateRoleRequest request);

    /**
     * Update an existing role
     */
    RoleDto updateRole(Long roleId, UpdateRoleRequest request);

    /**
     * Delete a role
     */
    void deleteRole(Long roleId);

    /**
     * Get role by ID
     */
    RoleDto getRoleById(Long roleId);

    /**
     * Get all roles
     */
    List<RoleDto> getAllRoles();

    /**
     * Get role by name
     */
    Role getRoleByName(RoleName roleName);

    /**
     * Assign role to user
     */
    void assignRoleToUser(UUID userId, Long roleId);

    /**
     * Remove role from user
     */
    void removeRoleFromUser(UUID userId, Long roleId);

    /**
     * Get user's roles
     */
    Set<Role> getUserRoles(UUID userId);

    /**
     * Check if role exists
     */
    boolean roleExists(String roleName);

    /**
     * Get default role
     */
    Role getDefaultRole();

    /**
     * Initialize default roles and permissions
     */
    void initializeDefaultRolesAndPermissions();
}
