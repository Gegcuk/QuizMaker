package uk.gegc.quizmaker.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.exception.ForbiddenException;
import uk.gegc.quizmaker.exception.UnauthorizedException;
import uk.gegc.quizmaker.model.user.PermissionName;
import uk.gegc.quizmaker.model.user.RoleName;
import uk.gegc.quizmaker.model.user.User;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionUtilTest {

    @Mock
    private PermissionEvaluator permissionEvaluator;

    @InjectMocks
    private PermissionUtil permissionUtil;

    @Test
    @DisplayName("requirePermission: does not throw when user has permission")
    void requirePermission_hasPermission() {
        // Given
        when(permissionEvaluator.hasPermission(PermissionName.QUIZ_CREATE)).thenReturn(true);
        
        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requirePermission(PermissionName.QUIZ_CREATE));
        verify(permissionEvaluator).hasPermission(PermissionName.QUIZ_CREATE);
    }

    @Test
    @DisplayName("requirePermission: throws ForbiddenException when user lacks permission")
    void requirePermission_lacksPermission() {
        // Given
        when(permissionEvaluator.hasPermission(PermissionName.QUIZ_CREATE)).thenReturn(false);
        
        // When & Then
        assertThrows(ForbiddenException.class, () -> 
            permissionUtil.requirePermission(PermissionName.QUIZ_CREATE)
        );
        verify(permissionEvaluator).hasPermission(PermissionName.QUIZ_CREATE);
    }

    @Test
    @DisplayName("requireAnyPermission: does not throw when user has at least one permission")
    void requireAnyPermission_hasOne() {
        // Given
        PermissionName[] permissions = {PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE};
        when(permissionEvaluator.hasAnyPermission(permissions)).thenReturn(true);
        
        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireAnyPermission(permissions));
        verify(permissionEvaluator).hasAnyPermission(permissions);
    }

    @Test
    @DisplayName("requireAnyPermission: throws ForbiddenException when user lacks all permissions")
    void requireAnyPermission_lacksAll() {
        // Given
        PermissionName[] permissions = {PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE};
        when(permissionEvaluator.hasAnyPermission(permissions)).thenReturn(false);
        
        // When & Then
        assertThrows(ForbiddenException.class, () -> 
            permissionUtil.requireAnyPermission(permissions)
        );
        verify(permissionEvaluator).hasAnyPermission(permissions);
    }

    @Test
    @DisplayName("requireAllPermissions: does not throw when user has all permissions")
    void requireAllPermissions_hasAll() {
        // Given
        PermissionName[] permissions = {PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE};
        when(permissionEvaluator.hasAllPermissions(permissions)).thenReturn(true);
        
        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireAllPermissions(permissions));
        verify(permissionEvaluator).hasAllPermissions(permissions);
    }

    @Test
    @DisplayName("requireAllPermissions: throws ForbiddenException when user lacks any permission")
    void requireAllPermissions_lacksOne() {
        // Given
        PermissionName[] permissions = {PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE};
        when(permissionEvaluator.hasAllPermissions(permissions)).thenReturn(false);
        
        // When & Then
        assertThrows(ForbiddenException.class, () -> 
            permissionUtil.requireAllPermissions(permissions)
        );
        verify(permissionEvaluator).hasAllPermissions(permissions);
    }

    @Test
    @DisplayName("requireRole: does not throw when user has role")
    void requireRole_hasRole() {
        // Given
        when(permissionEvaluator.hasRole(RoleName.ROLE_ADMIN)).thenReturn(true);
        
        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireRole(RoleName.ROLE_ADMIN));
        verify(permissionEvaluator).hasRole(RoleName.ROLE_ADMIN);
    }

    @Test
    @DisplayName("requireRole: throws ForbiddenException when user lacks role")
    void requireRole_lacksRole() {
        // Given
        when(permissionEvaluator.hasRole(RoleName.ROLE_ADMIN)).thenReturn(false);
        
        // When & Then
        assertThrows(ForbiddenException.class, () -> 
            permissionUtil.requireRole(RoleName.ROLE_ADMIN)
        );
        verify(permissionEvaluator).hasRole(RoleName.ROLE_ADMIN);
    }

    @Test
    @DisplayName("requireAnyRole: does not throw when user has at least one role")
    void requireAnyRole_hasOne() {
        // Given
        RoleName[] roles = {RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR};
        when(permissionEvaluator.hasAnyRole(roles)).thenReturn(true);
        
        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireAnyRole(roles));
        verify(permissionEvaluator).hasAnyRole(roles);
    }

    @Test
    @DisplayName("requireAnyRole: throws ForbiddenException when user lacks all roles")
    void requireAnyRole_lacksAll() {
        // Given
        RoleName[] roles = {RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR};
        when(permissionEvaluator.hasAnyRole(roles)).thenReturn(false);
        
        // When & Then
        assertThrows(ForbiddenException.class, () -> 
            permissionUtil.requireAnyRole(roles)
        );
        verify(permissionEvaluator).hasAnyRole(roles);
    }

    @Test
    @DisplayName("requireResourceOwnershipOrPermission: does not throw when user owns resource")
    void requireResourceOwnershipOrPermission_ownsResource() {
        // Given
        UUID resourceOwnerId = UUID.randomUUID();
        when(permissionEvaluator.canAccessResource(resourceOwnerId, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);
        
        // When & Then
        assertDoesNotThrow(() -> 
            permissionUtil.requireResourceOwnershipOrPermission(resourceOwnerId, PermissionName.QUIZ_ADMIN)
        );
        verify(permissionEvaluator).canAccessResource(resourceOwnerId, PermissionName.QUIZ_ADMIN);
    }

    @Test
    @DisplayName("requireResourceOwnershipOrPermission: throws ForbiddenException when user doesn't own and lacks permission")
    void requireResourceOwnershipOrPermission_noAccess() {
        // Given
        UUID resourceOwnerId = UUID.randomUUID();
        when(permissionEvaluator.canAccessResource(resourceOwnerId, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);
        
        // When & Then
        assertThrows(ForbiddenException.class, () -> 
            permissionUtil.requireResourceOwnershipOrPermission(resourceOwnerId, PermissionName.QUIZ_ADMIN)
        );
        verify(permissionEvaluator).canAccessResource(resourceOwnerId, PermissionName.QUIZ_ADMIN);
    }

    @Test
    @DisplayName("requireResourceOwnership: does not throw when user owns resource")
    void requireResourceOwnership_ownsResource() {
        // Given
        UUID resourceOwnerId = UUID.randomUUID();
        when(permissionEvaluator.isResourceOwner(resourceOwnerId)).thenReturn(true);
        
        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireResourceOwnership(resourceOwnerId));
        verify(permissionEvaluator).isResourceOwner(resourceOwnerId);
    }

    @Test
    @DisplayName("requireResourceOwnership: throws ForbiddenException when user doesn't own resource")
    void requireResourceOwnership_doesntOwnResource() {
        // Given
        UUID resourceOwnerId = UUID.randomUUID();
        when(permissionEvaluator.isResourceOwner(resourceOwnerId)).thenReturn(false);
        
        // When & Then
        assertThrows(ForbiddenException.class, () -> 
            permissionUtil.requireResourceOwnership(resourceOwnerId)
        );
        verify(permissionEvaluator).isResourceOwner(resourceOwnerId);
    }

    @Test
    @DisplayName("getCurrentUser: returns user when authenticated")
    void getCurrentUser_authenticated() {
        // Given
        User expectedUser = new User();
        when(permissionEvaluator.getCurrentUser()).thenReturn(expectedUser);
        
        // When
        User result = permissionUtil.getCurrentUser();
        
        // Then
        assertEquals(expectedUser, result);
        verify(permissionEvaluator).getCurrentUser();
    }

    @Test
    @DisplayName("getCurrentUserId: returns user ID when authenticated")
    void getCurrentUserId_authenticated() {
        // Given
        UUID expectedId = UUID.randomUUID();
        User user = new User();
        user.setId(expectedId);
        when(permissionEvaluator.getCurrentUser()).thenReturn(user);
        
        // When
        UUID result = permissionUtil.getCurrentUserId();
        
        // Then
        assertEquals(expectedId, result);
        verify(permissionEvaluator).getCurrentUser();
    }

    @Test
    @DisplayName("getCurrentUserId: throws UnauthorizedException when not authenticated")
    void getCurrentUserId_notAuthenticated() {
        // Given
        when(permissionEvaluator.getCurrentUser()).thenReturn(null);
        
        // When & Then
        assertThrows(UnauthorizedException.class, () -> 
            permissionUtil.getCurrentUserId()
        );
        verify(permissionEvaluator).getCurrentUser();
    }

    @Test
    @DisplayName("isAdmin: returns true when user is admin")
    void isAdmin_true() {
        // Given
        when(permissionEvaluator.isAdmin()).thenReturn(true);
        
        // When
        boolean result = permissionUtil.isAdmin();
        
        // Then
        assertTrue(result);
        verify(permissionEvaluator).isAdmin();
    }

    @Test
    @DisplayName("isAdmin: returns false when user is not admin")
    void isAdmin_false() {
        // Given
        when(permissionEvaluator.isAdmin()).thenReturn(false);
        
        // When
        boolean result = permissionUtil.isAdmin();
        
        // Then
        assertFalse(result);
        verify(permissionEvaluator).isAdmin();
    }

    @Test
    @DisplayName("isSuperAdmin: returns true when user is super admin")
    void isSuperAdmin_true() {
        // Given
        when(permissionEvaluator.isSuperAdmin()).thenReturn(true);
        
        // When
        boolean result = permissionUtil.isSuperAdmin();
        
        // Then
        assertTrue(result);
        verify(permissionEvaluator).isSuperAdmin();
    }

    @Test
    @DisplayName("isSuperAdmin: returns false when user is not super admin")
    void isSuperAdmin_false() {
        // Given
        when(permissionEvaluator.isSuperAdmin()).thenReturn(false);
        
        // When
        boolean result = permissionUtil.isSuperAdmin();
        
        // Then
        assertFalse(result);
        verify(permissionEvaluator).isSuperAdmin();
    }

    @Test
    @DisplayName("requireAuthentication: does not throw when user is authenticated")
    void requireAuthentication_authenticated() {
        // Given
        User user = new User();
        when(permissionEvaluator.getCurrentUser()).thenReturn(user);
        
        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireAuthentication());
        verify(permissionEvaluator).getCurrentUser();
    }

    @Test
    @DisplayName("requireAuthentication: throws UnauthorizedException when not authenticated")
    void requireAuthentication_notAuthenticated() {
        // Given
        when(permissionEvaluator.getCurrentUser()).thenReturn(null);
        
        // When & Then
        assertThrows(UnauthorizedException.class, () -> 
            permissionUtil.requireAuthentication()
        );
        verify(permissionEvaluator).getCurrentUser();
    }
} 