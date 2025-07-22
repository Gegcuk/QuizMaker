package uk.gegc.quizmaker.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.exception.ForbiddenException;
import uk.gegc.quizmaker.exception.UnauthorizedException;
import uk.gegc.quizmaker.model.user.PermissionName;
import uk.gegc.quizmaker.model.user.RoleName;
import uk.gegc.quizmaker.model.user.User;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PermissionUtil {

    private final PermissionEvaluator permissionEvaluator;

    /**
     * Ensure current user has the specified permission
     */
    public void requirePermission(PermissionName permission) {
        if (!permissionEvaluator.hasPermission(permission)) {
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    /**
     * Ensure current user has any of the specified permissions
     */
    public void requireAnyPermission(PermissionName... permissions) {
        if (!permissionEvaluator.hasAnyPermission(permissions)) {
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    /**
     * Ensure current user has all of the specified permissions
     */
    public void requireAllPermissions(PermissionName... permissions) {
        if (!permissionEvaluator.hasAllPermissions(permissions)) {
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    /**
     * Ensure current user has the specified role
     */
    public void requireRole(RoleName role) {
        if (!permissionEvaluator.hasRole(role)) {
            throw new ForbiddenException("Insufficient role to access this resource");
        }
    }

    /**
     * Ensure current user has any of the specified roles
     */
    public void requireAnyRole(RoleName... roles) {
        if (!permissionEvaluator.hasAnyRole(roles)) {
            throw new ForbiddenException("Insufficient role to access this resource");
        }
    }

    /**
     * Ensure current user owns the resource or has admin permission
     */
    public void requireResourceOwnershipOrPermission(UUID resourceOwnerId, PermissionName adminPermission) {
        if (!permissionEvaluator.canAccessResource(resourceOwnerId, adminPermission)) {
            throw new ForbiddenException("You can only access your own resources or need admin permissions");
        }
    }

    /**
     * Ensure current user owns the resource
     */
    public void requireResourceOwnership(UUID resourceOwnerId) {
        if (!permissionEvaluator.isResourceOwner(resourceOwnerId)) {
            throw new ForbiddenException("You can only access your own resources");
        }
    }

    /**
     * Get current authenticated user
     */
    public User getCurrentUser() {
        return permissionEvaluator.getCurrentUser();
    }

    /**
     * Get current user ID
     */
    public UUID getCurrentUserId() {
        User user = getCurrentUser();
        if (user == null) {
            throw new UnauthorizedException("User not authenticated");
        }
        return user.getId();
    }

    /**
     * Check if current user is admin
     */
    public boolean isAdmin() {
        return permissionEvaluator.isAdmin();
    }

    /**
     * Check if current user is super admin
     */
    public boolean isSuperAdmin() {
        return permissionEvaluator.isSuperAdmin();
    }

    /**
     * Ensure current user is authenticated
     */
    public void requireAuthentication() {
        if (getCurrentUser() == null) {
            throw new UnauthorizedException("Authentication required");
        }
    }
} 