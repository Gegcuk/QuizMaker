package uk.gegc.quizmaker.shared.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PermissionUtil {

    private final AppPermissionEvaluator appPermissionEvaluator;

    /**
     * Ensure current user has the specified permission
     */
    public void requirePermission(PermissionName permission) {
        if (!appPermissionEvaluator.hasPermission(permission)) {
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    /**
     * Ensure current user has any of the specified permissions
     */
    public void requireAnyPermission(PermissionName... permissions) {
        if (!appPermissionEvaluator.hasAnyPermission(permissions)) {
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    /**
     * Ensure current user has all of the specified permissions
     */
    public void requireAllPermissions(PermissionName... permissions) {
        if (!appPermissionEvaluator.hasAllPermissions(permissions)) {
            throw new ForbiddenException("Insufficient permissions to access this resource");
        }
    }

    /**
     * Ensure current user has the specified role
     */
    public void requireRole(RoleName role) {
        if (!appPermissionEvaluator.hasRole(role)) {
            throw new ForbiddenException("Insufficient role to access this resource");
        }
    }

    /**
     * Ensure current user has any of the specified roles
     */
    public void requireAnyRole(RoleName... roles) {
        if (!appPermissionEvaluator.hasAnyRole(roles)) {
            throw new ForbiddenException("Insufficient role to access this resource");
        }
    }

    /**
     * Ensure current user owns the resource or has admin permission
     */
    public void requireResourceOwnershipOrPermission(UUID resourceOwnerId, PermissionName adminPermission) {
        if (!appPermissionEvaluator.canAccessResource(resourceOwnerId, adminPermission)) {
            throw new ForbiddenException("You can only access your own resources or need admin permissions");
        }
    }

    /**
     * Ensure current user owns the resource
     */
    public void requireResourceOwnership(UUID resourceOwnerId) {
        if (!appPermissionEvaluator.isResourceOwner(resourceOwnerId)) {
            throw new ForbiddenException("You can only access your own resources");
        }
    }

    /**
     * Get current authenticated user
     */
    public User getCurrentUser() {
        return appPermissionEvaluator.getCurrentUser();
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
        return appPermissionEvaluator.isAdmin();
    }

    /**
     * Check if current user is super admin
     */
    public boolean isSuperAdmin() {
        return appPermissionEvaluator.isSuperAdmin();
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