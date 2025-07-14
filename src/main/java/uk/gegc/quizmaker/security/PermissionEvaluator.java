package uk.gegc.quizmaker.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.model.user.*;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionEvaluator {
    
    private final UserRepository userRepository;
    
    /**
     * Check if current user has the specified permission
     */
    public boolean hasPermission(PermissionName permission) {
        return hasPermission(getCurrentUser(), permission);
    }
    
    /**
     * Check if user has the specified permission
     */
    public boolean hasPermission(User user, PermissionName permission) {
        if (user == null) {
            return false;
        }
        
        return user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .anyMatch(p -> p.getPermissionName().equals(permission.name()));
    }
    
    /**
     * Check if current user has any of the specified permissions
     */
    public boolean hasAnyPermission(PermissionName... permissions) {
        User user = getCurrentUser();
        if (user == null) {
            return false;
        }
        
        for (PermissionName permission : permissions) {
            if (hasPermission(user, permission)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if current user has all of the specified permissions
     */
    public boolean hasAllPermissions(PermissionName... permissions) {
        User user = getCurrentUser();
        if (user == null) {
            return false;
        }
        
        for (PermissionName permission : permissions) {
            if (!hasPermission(user, permission)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Check if current user has the specified role
     */
    public boolean hasRole(RoleName roleName) {
        return hasRole(getCurrentUser(), roleName);
    }
    
    /**
     * Check if user has the specified role
     */
    public boolean hasRole(User user, RoleName roleName) {
        if (user == null) {
            return false;
        }
        
        return user.getRoles().stream()
                .anyMatch(role -> role.getRoleName().equals(roleName.name()));
    }
    
    /**
     * Check if current user has any of the specified roles
     */
    public boolean hasAnyRole(RoleName... roleNames) {
        User user = getCurrentUser();
        if (user == null) {
            return false;
        }
        
        for (RoleName roleName : roleNames) {
            if (hasRole(user, roleName)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if current user has all of the specified roles
     */
    public boolean hasAllRoles(RoleName... roleNames) {
        User user = getCurrentUser();
        if (user == null) {
            return false;
        }
        
        for (RoleName roleName : roleNames) {
            if (!hasRole(user, roleName)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Check if current user owns the specified resource
     */
    public boolean isResourceOwner(UUID resourceOwnerId) {
        User currentUser = getCurrentUser();
        return currentUser != null && currentUser.getId().equals(resourceOwnerId);
    }
    
    /**
     * Check if current user can access resource (either owns it or has admin permission)
     */
    public boolean canAccessResource(UUID resourceOwnerId, PermissionName adminPermission) {
        return isResourceOwner(resourceOwnerId) || hasPermission(adminPermission);
    }
    
    /**
     * Get current authenticated user
     */
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || 
            "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        
        String username = authentication.getName();
        return userRepository.findByUsername(username).orElse(null);
    }
    
    /**
     * Get current user's permissions
     */
    public Set<String> getCurrentUserPermissions() {
        User user = getCurrentUser();
        if (user == null) {
            return Set.of();
        }
        
        return user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getPermissionName)
                .collect(Collectors.toSet());
    }
    
    /**
     * Get current user's roles
     */
    public Set<String> getCurrentUserRoles() {
        User user = getCurrentUser();
        if (user == null) {
            return Set.of();
        }
        
        return user.getRoles().stream()
                .map(Role::getRoleName)
                .collect(Collectors.toSet());
    }
    
    /**
     * Check if user is admin (has SYSTEM_ADMIN permission)
     */
    public boolean isAdmin() {
        return hasPermission(PermissionName.SYSTEM_ADMIN);
    }
    
    /**
     * Check if user is super admin (has SUPER_ADMIN role)
     */
    public boolean isSuperAdmin() {
        return hasRole(RoleName.ROLE_SUPER_ADMIN);
    }
} 