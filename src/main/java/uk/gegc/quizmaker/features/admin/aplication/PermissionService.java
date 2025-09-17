package uk.gegc.quizmaker.features.admin.aplication;

import uk.gegc.quizmaker.features.user.domain.model.Permission;

import java.util.List;
import java.util.Set;

public interface PermissionService {

    /**
     * Create a new permission
     */
    Permission createPermission(String permissionName, String description, String resource, String action);

    /**
     * Get permission by name
     */
    Permission getPermissionByName(String permissionName);

    /**
     * Get all permissions
     */
    List<Permission> getAllPermissions();

    /**
     * Get permission by id
     */
    Permission getPermissionById(Long permissionId);

    /**
     * Get permissions by resource
     */
    List<Permission> getPermissionsByResource(String resource);

    /**
     * Assign permission to role
     */
    void assignPermissionToRole(Long roleId, Long permissionId);

    /**
     * Remove permission from role
     */
    void removePermissionFromRole(Long roleId, Long permissionId);

    /**
     * Get role's permissions
     */
    Set<Permission> getRolePermissions(Long roleId);

    /**
     * Check if permission exists
     */
    boolean permissionExists(String permissionName);

    /**
     * Initialize all permissions from PermissionName enum
     */
    void initializePermissions();

    /**
     * Delete permission
     */
    void deletePermission(Long permissionId);

    /**
     * Update permission
     */
    Permission updatePermission(Long permissionId, String description, String resource, String action);
}

