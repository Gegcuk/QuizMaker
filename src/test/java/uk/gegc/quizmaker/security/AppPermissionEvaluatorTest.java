package uk.gegc.quizmaker.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import uk.gegc.quizmaker.model.user.*;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppPermissionEvaluatorTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private AppPermissionEvaluator appPermissionEvaluator;

    private User testUser;
    private Role testRole;
    private Permission testPermission;

    @BeforeEach
    void setUp() {
        // Set up SecurityContextHolder
        SecurityContextHolder.setContext(securityContext);

        // Create test permission
        testPermission = Permission.builder()
                .permissionId(1L)
                .permissionName(PermissionName.QUIZ_READ.name())
                .build();

        // Create test role with permission
        testRole = Role.builder()
                .roleId(1L)
                .roleName(RoleName.ROLE_USER.name())
                .permissions(Set.of(testPermission))
                .build();

        // Create test user with role
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");
        testUser.setRoles(Set.of(testRole));
    }

    @Test
    @DisplayName("hasPermission: returns false when user is null")
    void hasPermission_nullUser() {
        // When
        boolean result = appPermissionEvaluator.hasPermission(null, PermissionName.QUIZ_READ);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasPermission: returns true when user has permission")
    void hasPermission_userHasPermission() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasPermission(PermissionName.QUIZ_READ);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("hasPermission: returns false when user lacks permission")
    void hasPermission_userLacksPermission() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasPermission(PermissionName.QUIZ_CREATE);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasAnyPermission: returns false when user is null")
    void hasAnyPermission_nullUser() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.empty());

        // When
        boolean result = appPermissionEvaluator.hasAnyPermission(PermissionName.QUIZ_READ, PermissionName.QUIZ_CREATE);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasAnyPermission: returns true when user has one permission")
    void hasAnyPermission_hasOne() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasAnyPermission(PermissionName.QUIZ_READ, PermissionName.QUIZ_CREATE);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("hasAnyPermission: returns false when user has none")
    void hasAnyPermission_hasNone() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasAnyPermission(PermissionName.QUIZ_CREATE, PermissionName.QUIZ_DELETE);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasAllPermissions: returns true when user has all permissions")
    void hasAllPermissions_hasAll() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasAllPermissions(PermissionName.QUIZ_READ);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("hasAllPermissions: returns false when user lacks one permission")
    void hasAllPermissions_lacksOne() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasAllPermissions(PermissionName.QUIZ_READ, PermissionName.QUIZ_CREATE);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasRole: returns true when user has role")
    void hasRole_userHasRole() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasRole(RoleName.ROLE_USER);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("hasRole: returns false when user lacks role")
    void hasRole_userLacksRole() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasRole(RoleName.ROLE_ADMIN);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasAnyRole: returns true when user has one role")
    void hasAnyRole_hasOne() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasAnyRole(RoleName.ROLE_USER, RoleName.ROLE_ADMIN);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("hasAllRoles: returns true when user has all roles")
    void hasAllRoles_hasAll() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.hasAllRoles(RoleName.ROLE_USER);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("isResourceOwner: returns true when user owns resource")
    void isResourceOwner_ownsResource() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.isResourceOwner(testUser.getId());

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("isResourceOwner: returns false when user doesn't own resource")
    void isResourceOwner_doesntOwnResource() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.isResourceOwner(UUID.randomUUID());

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("canAccessResource: returns true when user owns resource")
    void canAccessResource_ownsResource() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.canAccessResource(testUser.getId(), PermissionName.QUIZ_ADMIN);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("canAccessResource: returns true when user has admin permission")
    void canAccessResource_hasAdminPermission() {
        // Given
        Permission adminPermission = Permission.builder()
                .permissionId(2L)
                .permissionName(PermissionName.QUIZ_ADMIN.name())
                .build();

        Role adminRole = Role.builder()
                .roleId(2L)
                .roleName(RoleName.ROLE_ADMIN.name())
                .permissions(Set.of(testPermission, adminPermission))
                .build();

        testUser.setRoles(Set.of(adminRole));

        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.canAccessResource(UUID.randomUUID(), PermissionName.QUIZ_ADMIN);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("getCurrentUser: returns user when authenticated")
    void getCurrentUser_authenticated() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        User result = appPermissionEvaluator.getCurrentUser();

        // Then
        assertEquals(testUser, result);
    }

    @Test
    @DisplayName("getCurrentUser: returns null when not authenticated")
    void getCurrentUser_notAuthenticated() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(false);

        // When
        User result = appPermissionEvaluator.getCurrentUser();

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("getCurrentUser: returns null when authentication is null")
    void getCurrentUser_nullAuthentication() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(null);

        // When
        User result = appPermissionEvaluator.getCurrentUser();

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("getCurrentUserPermissions: returns permissions when user exists")
    void getCurrentUserPermissions() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        Set<String> result = appPermissionEvaluator.getCurrentUserPermissions();

        // Then
        assertTrue(result.contains(PermissionName.QUIZ_READ.name()));
    }

    @Test
    @DisplayName("getCurrentUserRoles: returns roles when user exists")
    void getCurrentUserRoles() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        Set<String> result = appPermissionEvaluator.getCurrentUserRoles();

        // Then
        assertTrue(result.contains(RoleName.ROLE_USER.name()));
    }

    @Test
    @DisplayName("isAdmin: returns true when user has SYSTEM_ADMIN permission")
    void isAdmin_hasSystemAdmin() {
        // Given
        Permission systemAdminPermission = Permission.builder()
                .permissionId(3L)
                .permissionName(PermissionName.SYSTEM_ADMIN.name())
                .build();

        Role adminRole = Role.builder()
                .roleId(3L)
                .roleName(RoleName.ROLE_ADMIN.name())
                .permissions(Set.of(systemAdminPermission))
                .build();

        testUser.setRoles(Set.of(adminRole));

        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.isAdmin();

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("isSuperAdmin: returns true when user has SUPER_ADMIN role")
    void isSuperAdmin_hasSuperAdminRole() {
        // Given
        Role superAdminRole = Role.builder()
                .roleId(4L)
                .roleName(RoleName.ROLE_SUPER_ADMIN.name())
                .permissions(Set.of(testPermission))
                .build();

        testUser.setRoles(Set.of(superAdminRole));

        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsernameWithRolesAndPermissions("testuser")).thenReturn(Optional.of(testUser));

        // When
        boolean result = appPermissionEvaluator.isSuperAdmin();

        // Then
        assertTrue(result);
    }
} 