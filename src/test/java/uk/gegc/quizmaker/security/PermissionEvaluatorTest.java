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

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionEvaluatorTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private PermissionEvaluator permissionEvaluator;

    private User testUser;
    private Role userRole;
    private Role adminRole;
    private Permission readPermission;
    private Permission writePermission;
    private Permission adminPermission;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.setContext(securityContext);
        
        // Set up permissions
        readPermission = Permission.builder()
                .permissionId(1L)
                .permissionName(PermissionName.QUIZ_READ.name())
                .build();
        
        writePermission = Permission.builder()
                .permissionId(2L)
                .permissionName(PermissionName.QUIZ_CREATE.name())
                .build();
        
        adminPermission = Permission.builder()
                .permissionId(3L)
                .permissionName(PermissionName.SYSTEM_ADMIN.name())
                .build();
        
        // Set up roles
        userRole = Role.builder()
                .roleId(1L)
                .roleName(RoleName.ROLE_USER.name())
                .permissions(Set.of(readPermission))
                .build();
        
        adminRole = Role.builder()
                .roleId(2L)
                .roleName(RoleName.ROLE_ADMIN.name())
                .permissions(Set.of(readPermission, writePermission, adminPermission))
                .build();
        
        // Set up test user
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");
        testUser.setRoles(new HashSet<>(Set.of(userRole)));
    }

    @Test
    @DisplayName("hasPermission: returns true when user has permission")
    void hasPermission_userHasPermission() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasPermission(PermissionName.QUIZ_READ);
        
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
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasPermission(PermissionName.QUIZ_CREATE);
        
        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasPermission: returns false when user is null")
    void hasPermission_nullUser() {
        // When
        boolean result = permissionEvaluator.hasPermission(null, PermissionName.QUIZ_READ);
        
        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasAnyPermission: returns true when user has at least one permission")
    void hasAnyPermission_hasOne() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasAnyPermission(
                PermissionName.QUIZ_READ, 
                PermissionName.QUIZ_CREATE
        );
        
        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("hasAnyPermission: returns false when user has none of the permissions")
    void hasAnyPermission_hasNone() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasAnyPermission(
                PermissionName.QUIZ_CREATE, 
                PermissionName.QUIZ_DELETE
        );
        
        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasAllPermissions: returns true when user has all permissions")
    void hasAllPermissions_hasAll() {
        // Given
        testUser.setRoles(Set.of(adminRole));
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasAllPermissions(
                PermissionName.QUIZ_READ, 
                PermissionName.QUIZ_CREATE
        );
        
        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("hasAllPermissions: returns false when user lacks any permission")
    void hasAllPermissions_lacksOne() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasAllPermissions(
                PermissionName.QUIZ_READ, 
                PermissionName.QUIZ_CREATE
        );
        
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
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasRole(RoleName.ROLE_USER);
        
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
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasRole(RoleName.ROLE_ADMIN);
        
        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("hasAnyRole: returns true when user has at least one role")
    void hasAnyRole_hasOne() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasAnyRole(
                RoleName.ROLE_USER, 
                RoleName.ROLE_ADMIN
        );
        
        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("hasAllRoles: returns true when user has all roles")
    void hasAllRoles_hasAll() {
        // Given
        testUser.setRoles(Set.of(userRole, adminRole));
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.hasAllRoles(
                RoleName.ROLE_USER, 
                RoleName.ROLE_ADMIN
        );
        
        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("isResourceOwner: returns true when user owns resource")
    void isResourceOwner_ownsResource() {
        // Given
        UUID userId = testUser.getId();
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.isResourceOwner(userId);
        
        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("isResourceOwner: returns false when user doesn't own resource")
    void isResourceOwner_doesntOwnResource() {
        // Given
        UUID differentUserId = UUID.randomUUID();
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.isResourceOwner(differentUserId);
        
        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("canAccessResource: returns true when user owns resource")
    void canAccessResource_ownsResource() {
        // Given
        UUID userId = testUser.getId();
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.canAccessResource(userId, PermissionName.QUIZ_ADMIN);
        
        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("canAccessResource: returns true when user has admin permission")
    void canAccessResource_hasAdminPermission() {
        // Given
        UUID differentUserId = UUID.randomUUID();
        testUser.setRoles(Set.of(adminRole));
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.canAccessResource(differentUserId, PermissionName.SYSTEM_ADMIN);
        
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
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        User result = permissionEvaluator.getCurrentUser();
        
        // Then
        assertEquals(testUser, result);
    }

    @Test
    @DisplayName("getCurrentUser: returns null when not authenticated")
    void getCurrentUser_notAuthenticated() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(null);
        
        // When
        User result = permissionEvaluator.getCurrentUser();
        
        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("getCurrentUser: returns null when anonymous user")
    void getCurrentUser_anonymousUser() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("anonymousUser");
        
        // When
        User result = permissionEvaluator.getCurrentUser();
        
        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("getCurrentUserPermissions: returns user's permissions")
    void getCurrentUserPermissions() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        Set<String> permissions = permissionEvaluator.getCurrentUserPermissions();
        
        // Then
        assertTrue(permissions.contains(readPermission.getPermissionName()));
        assertEquals(1, permissions.size());
    }

    @Test
    @DisplayName("getCurrentUserRoles: returns user's roles")
    void getCurrentUserRoles() {
        // Given
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        Set<String> roles = permissionEvaluator.getCurrentUserRoles();
        
        // Then
        assertTrue(roles.contains(userRole.getRoleName()));
        assertEquals(1, roles.size());
    }

    @Test
    @DisplayName("isAdmin: returns true when user has SYSTEM_ADMIN permission")
    void isAdmin_hasSystemAdmin() {
        // Given
        testUser.setRoles(Set.of(adminRole));
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.isAdmin();
        
        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("isSuperAdmin: returns true when user has ROLE_SUPER_ADMIN")
    void isSuperAdmin_hasSuperAdminRole() {
        // Given
        Role superAdminRole = Role.builder()
                .roleId(3L)
                .roleName(RoleName.ROLE_SUPER_ADMIN.name())
                .build();
        testUser.setRoles(Set.of(superAdminRole));
        
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When
        boolean result = permissionEvaluator.isSuperAdmin();
        
        // Then
        assertTrue(result);
    }
} 