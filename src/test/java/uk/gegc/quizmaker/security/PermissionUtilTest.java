package uk.gegc.quizmaker.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.PermissionUtil;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionUtilTest {

    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;

    @InjectMocks
    private PermissionUtil permissionUtil;

    @Test
    @DisplayName("requirePermission: does not throw when user has permission")
    void requirePermission_hasPermission() {
        // Given
        when(appPermissionEvaluator.hasPermission(PermissionName.QUIZ_CREATE)).thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requirePermission(PermissionName.QUIZ_CREATE));
        verify(appPermissionEvaluator).hasPermission(PermissionName.QUIZ_CREATE);
    }

    @Test
    @DisplayName("requirePermission: throws ForbiddenException when user lacks permission")
    void requirePermission_lacksPermission() {
        // Given
        when(appPermissionEvaluator.hasPermission(PermissionName.QUIZ_CREATE)).thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionUtil.requirePermission(PermissionName.QUIZ_CREATE)
        );
        verify(appPermissionEvaluator).hasPermission(PermissionName.QUIZ_CREATE);
    }

    @Test
    @DisplayName("requireAnyPermission: does not throw when user has at least one permission")
    void requireAnyPermission_hasOne() {
        // Given
        PermissionName[] permissions = {PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE};
        when(appPermissionEvaluator.hasAnyPermission(permissions)).thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireAnyPermission(permissions));
        verify(appPermissionEvaluator).hasAnyPermission(permissions);
    }

    @Test
    @DisplayName("requireAnyPermission: throws ForbiddenException when user lacks all permissions")
    void requireAnyPermission_lacksAll() {
        // Given
        PermissionName[] permissions = {PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE};
        when(appPermissionEvaluator.hasAnyPermission(permissions)).thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionUtil.requireAnyPermission(permissions)
        );
        verify(appPermissionEvaluator).hasAnyPermission(permissions);
    }

    @Test
    @DisplayName("requireAllPermissions: does not throw when user has all permissions")
    void requireAllPermissions_hasAll() {
        // Given
        PermissionName[] permissions = {PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE};
        when(appPermissionEvaluator.hasAllPermissions(permissions)).thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireAllPermissions(permissions));
        verify(appPermissionEvaluator).hasAllPermissions(permissions);
    }

    @Test
    @DisplayName("requireAllPermissions: throws ForbiddenException when user lacks any permission")
    void requireAllPermissions_lacksOne() {
        // Given
        PermissionName[] permissions = {PermissionName.QUIZ_CREATE, PermissionName.QUIZ_UPDATE};
        when(appPermissionEvaluator.hasAllPermissions(permissions)).thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionUtil.requireAllPermissions(permissions)
        );
        verify(appPermissionEvaluator).hasAllPermissions(permissions);
    }

    @Test
    @DisplayName("requireRole: does not throw when user has role")
    void requireRole_hasRole() {
        // Given
        when(appPermissionEvaluator.hasRole(RoleName.ROLE_ADMIN)).thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireRole(RoleName.ROLE_ADMIN));
        verify(appPermissionEvaluator).hasRole(RoleName.ROLE_ADMIN);
    }

    @Test
    @DisplayName("requireRole: throws ForbiddenException when user lacks role")
    void requireRole_lacksRole() {
        // Given
        when(appPermissionEvaluator.hasRole(RoleName.ROLE_ADMIN)).thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionUtil.requireRole(RoleName.ROLE_ADMIN)
        );
        verify(appPermissionEvaluator).hasRole(RoleName.ROLE_ADMIN);
    }

    @Test
    @DisplayName("requireAnyRole: does not throw when user has at least one role")
    void requireAnyRole_hasOne() {
        // Given
        RoleName[] roles = {RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR};
        when(appPermissionEvaluator.hasAnyRole(roles)).thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireAnyRole(roles));
        verify(appPermissionEvaluator).hasAnyRole(roles);
    }

    @Test
    @DisplayName("requireAnyRole: throws ForbiddenException when user lacks all roles")
    void requireAnyRole_lacksAll() {
        // Given
        RoleName[] roles = {RoleName.ROLE_ADMIN, RoleName.ROLE_MODERATOR};
        when(appPermissionEvaluator.hasAnyRole(roles)).thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionUtil.requireAnyRole(roles)
        );
        verify(appPermissionEvaluator).hasAnyRole(roles);
    }

    @Test
    @DisplayName("requireResourceOwnershipOrPermission: does not throw when user owns resource")
    void requireResourceOwnershipOrPermission_ownsResource() {
        // Given
        UUID resourceOwnerId = UUID.randomUUID();
        when(appPermissionEvaluator.canAccessResource(resourceOwnerId, PermissionName.QUIZ_ADMIN))
                .thenReturn(true);

        // When & Then
        assertDoesNotThrow(() ->
                permissionUtil.requireResourceOwnershipOrPermission(resourceOwnerId, PermissionName.QUIZ_ADMIN)
        );
        verify(appPermissionEvaluator).canAccessResource(resourceOwnerId, PermissionName.QUIZ_ADMIN);
    }

    @Test
    @DisplayName("requireResourceOwnershipOrPermission: throws ForbiddenException when user doesn't own and lacks permission")
    void requireResourceOwnershipOrPermission_noAccess() {
        // Given
        UUID resourceOwnerId = UUID.randomUUID();
        when(appPermissionEvaluator.canAccessResource(resourceOwnerId, PermissionName.QUIZ_ADMIN))
                .thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionUtil.requireResourceOwnershipOrPermission(resourceOwnerId, PermissionName.QUIZ_ADMIN)
        );
        verify(appPermissionEvaluator).canAccessResource(resourceOwnerId, PermissionName.QUIZ_ADMIN);
    }

    @Test
    @DisplayName("requireResourceOwnership: does not throw when user owns resource")
    void requireResourceOwnership_ownsResource() {
        // Given
        UUID resourceOwnerId = UUID.randomUUID();
        when(appPermissionEvaluator.isResourceOwner(resourceOwnerId)).thenReturn(true);

        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireResourceOwnership(resourceOwnerId));
        verify(appPermissionEvaluator).isResourceOwner(resourceOwnerId);
    }

    @Test
    @DisplayName("requireResourceOwnership: throws ForbiddenException when user doesn't own resource")
    void requireResourceOwnership_doesntOwnResource() {
        // Given
        UUID resourceOwnerId = UUID.randomUUID();
        when(appPermissionEvaluator.isResourceOwner(resourceOwnerId)).thenReturn(false);

        // When & Then
        assertThrows(ForbiddenException.class, () ->
                permissionUtil.requireResourceOwnership(resourceOwnerId)
        );
        verify(appPermissionEvaluator).isResourceOwner(resourceOwnerId);
    }

    @Test
    @DisplayName("getCurrentUser: returns user when authenticated")
    void getCurrentUser_authenticated() {
        // Given
        User expectedUser = new User();
        when(appPermissionEvaluator.getCurrentUser()).thenReturn(expectedUser);

        // When
        User result = permissionUtil.getCurrentUser();

        // Then
        assertEquals(expectedUser, result);
        verify(appPermissionEvaluator).getCurrentUser();
    }

    @Test
    @DisplayName("getCurrentUserId: returns user ID when authenticated")
    void getCurrentUserId_authenticated() {
        // Given
        UUID expectedId = UUID.randomUUID();
        User user = new User();
        user.setId(expectedId);
        when(appPermissionEvaluator.getCurrentUser()).thenReturn(user);

        // When
        UUID result = permissionUtil.getCurrentUserId();

        // Then
        assertEquals(expectedId, result);
        verify(appPermissionEvaluator).getCurrentUser();
    }

    @Test
    @DisplayName("getCurrentUserId: throws UnauthorizedException when not authenticated")
    void getCurrentUserId_notAuthenticated() {
        // Given
        when(appPermissionEvaluator.getCurrentUser()).thenReturn(null);

        // When & Then
        assertThrows(UnauthorizedException.class, () ->
                permissionUtil.getCurrentUserId()
        );
        verify(appPermissionEvaluator).getCurrentUser();
    }

    @Test
    @DisplayName("isAdmin: returns true when user is admin")
    void isAdmin_true() {
        // Given
        when(appPermissionEvaluator.isAdmin()).thenReturn(true);

        // When
        boolean result = permissionUtil.isAdmin();

        // Then
        assertTrue(result);
        verify(appPermissionEvaluator).isAdmin();
    }

    @Test
    @DisplayName("isAdmin: returns false when user is not admin")
    void isAdmin_false() {
        // Given
        when(appPermissionEvaluator.isAdmin()).thenReturn(false);

        // When
        boolean result = permissionUtil.isAdmin();

        // Then
        assertFalse(result);
        verify(appPermissionEvaluator).isAdmin();
    }

    @Test
    @DisplayName("isSuperAdmin: returns true when user is super admin")
    void isSuperAdmin_true() {
        // Given
        when(appPermissionEvaluator.isSuperAdmin()).thenReturn(true);

        // When
        boolean result = permissionUtil.isSuperAdmin();

        // Then
        assertTrue(result);
        verify(appPermissionEvaluator).isSuperAdmin();
    }

    @Test
    @DisplayName("isSuperAdmin: returns false when user is not super admin")
    void isSuperAdmin_false() {
        // Given
        when(appPermissionEvaluator.isSuperAdmin()).thenReturn(false);

        // When
        boolean result = permissionUtil.isSuperAdmin();

        // Then
        assertFalse(result);
        verify(appPermissionEvaluator).isSuperAdmin();
    }

    @Test
    @DisplayName("requireAuthentication: does not throw when user is authenticated")
    void requireAuthentication_authenticated() {
        // Given
        User user = new User();
        when(appPermissionEvaluator.getCurrentUser()).thenReturn(user);

        // When & Then
        assertDoesNotThrow(() -> permissionUtil.requireAuthentication());
        verify(appPermissionEvaluator).getCurrentUser();
    }

    @Test
    @DisplayName("requireAuthentication: throws UnauthorizedException when not authenticated")
    void requireAuthentication_notAuthenticated() {
        // Given
        when(appPermissionEvaluator.getCurrentUser()).thenReturn(null);

        // When & Then
        assertThrows(UnauthorizedException.class, () ->
                permissionUtil.requireAuthentication()
        );
        verify(appPermissionEvaluator).getCurrentUser();
    }
} 