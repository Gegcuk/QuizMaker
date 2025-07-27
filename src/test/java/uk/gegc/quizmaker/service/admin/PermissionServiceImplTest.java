package uk.gegc.quizmaker.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.user.Permission;
import uk.gegc.quizmaker.model.user.PermissionName;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.repository.user.PermissionRepository;
import uk.gegc.quizmaker.repository.user.RoleRepository;
import uk.gegc.quizmaker.service.admin.impl.PermissionServiceImpl;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private RoleRepository roleRepository;

    @InjectMocks
    private PermissionServiceImpl permissionService;

    @Test
    @DisplayName("createPermission: successfully creates new permission")
    void createPermission_success() {
        // Given
        String permissionName = "TEST_PERMISSION";
        String description = "Test permission";
        String resource = "test";
        String action = "read";

        when(permissionRepository.existsByPermissionName(permissionName)).thenReturn(false);

        Permission expectedPermission = Permission.builder()
                .permissionId(1L)
                .permissionName(permissionName)
                .description(description)
                .resource(resource)
                .action(action)
                .build();

        when(permissionRepository.save(any(Permission.class))).thenReturn(expectedPermission);

        // When
        Permission result = permissionService.createPermission(permissionName, description, resource, action);

        // Then
        assertNotNull(result);
        assertEquals(permissionName, result.getPermissionName());
        assertEquals(description, result.getDescription());
        assertEquals(resource, result.getResource());
        assertEquals(action, result.getAction());

        verify(permissionRepository).existsByPermissionName(permissionName);
        verify(permissionRepository).save(any(Permission.class));
    }

    @Test
    @DisplayName("createPermission: throws exception when permission already exists")
    void createPermission_alreadyExists() {
        // Given
        String permissionName = "EXISTING_PERMISSION";
        when(permissionRepository.existsByPermissionName(permissionName)).thenReturn(true);

        // When & Then
        assertThrows(IllegalArgumentException.class, () ->
                permissionService.createPermission(permissionName, "desc", "resource", "action")
        );

        verify(permissionRepository).existsByPermissionName(permissionName);
        verify(permissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("getPermissionByName: returns permission when found")
    void getPermissionByName_found() {
        // Given
        String permissionName = "TEST_PERMISSION";
        Permission expectedPermission = Permission.builder()
                .permissionId(1L)
                .permissionName(permissionName)
                .build();

        when(permissionRepository.findByPermissionName(permissionName))
                .thenReturn(Optional.of(expectedPermission));

        // When
        Permission result = permissionService.getPermissionByName(permissionName);

        // Then
        assertNotNull(result);
        assertEquals(expectedPermission, result);
        verify(permissionRepository).findByPermissionName(permissionName);
    }

    @Test
    @DisplayName("getPermissionByName: throws exception when not found")
    void getPermissionByName_notFound() {
        // Given
        String permissionName = "NON_EXISTENT";
        when(permissionRepository.findByPermissionName(permissionName))
                .thenReturn(Optional.empty());

        // When & Then
        assertThrows(ResourceNotFoundException.class, () ->
                permissionService.getPermissionByName(permissionName)
        );

        verify(permissionRepository).findByPermissionName(permissionName);
    }

    @Test
    @DisplayName("getAllPermissions: returns all permissions")
    void getAllPermissions() {
        // Given
        List<Permission> expectedPermissions = Arrays.asList(
                Permission.builder().permissionId(1L).permissionName("PERM1").build(),
                Permission.builder().permissionId(2L).permissionName("PERM2").build()
        );

        when(permissionRepository.findAll()).thenReturn(expectedPermissions);

        // When
        List<Permission> result = permissionService.getAllPermissions();

        // Then
        assertEquals(expectedPermissions, result);
        verify(permissionRepository).findAll();
    }

    @Test
    @DisplayName("getPermissionsByResource: returns permissions for specific resource")
    void getPermissionsByResource() {
        // Given
        String resource = "quiz";
        List<Permission> expectedPermissions = Arrays.asList(
                Permission.builder().permissionId(1L).permissionName("QUIZ_READ").resource(resource).build(),
                Permission.builder().permissionId(2L).permissionName("QUIZ_CREATE").resource(resource).build()
        );

        when(permissionRepository.findByResource(resource)).thenReturn(expectedPermissions);

        // When
        List<Permission> result = permissionService.getPermissionsByResource(resource);

        // Then
        assertEquals(expectedPermissions, result);
        verify(permissionRepository).findByResource(resource);
    }

    @Test
    @DisplayName("assignPermissionToRole: successfully assigns permission to role")
    void assignPermissionToRole_success() {
        // Given
        Long roleId = 1L;
        Long permissionId = 1L;

        Role role = Role.builder()
                .roleId(roleId)
                .roleName("TEST_ROLE")
                .permissions(new HashSet<>())
                .build();

        Permission permission = Permission.builder()
                .permissionId(permissionId)
                .permissionName("TEST_PERMISSION")
                .build();

        when(roleRepository.findByIdWithPermissions(roleId)).thenReturn(Optional.of(role));
        when(permissionRepository.findById(permissionId)).thenReturn(Optional.of(permission));
        when(roleRepository.save(any(Role.class))).thenReturn(role);

        // When
        permissionService.assignPermissionToRole(roleId, permissionId);

        // Then
        assertTrue(role.getPermissions().contains(permission));
        verify(roleRepository).findByIdWithPermissions(roleId);
        verify(permissionRepository).findById(permissionId);
        verify(roleRepository).save(role);
    }

    @Test
    @DisplayName("assignPermissionToRole: throws exception when role not found")
    void assignPermissionToRole_roleNotFound() {
        // Given
        Long roleId = 1L;
        Long permissionId = 1L;

        when(roleRepository.findByIdWithPermissions(roleId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(ResourceNotFoundException.class, () ->
                permissionService.assignPermissionToRole(roleId, permissionId)
        );

        verify(roleRepository).findByIdWithPermissions(roleId);
        verify(permissionRepository, never()).findById(any());
        verify(roleRepository, never()).save(any());
    }

    @Test
    @DisplayName("removePermissionFromRole: successfully removes permission from role")
    void removePermissionFromRole_success() {
        // Given
        Long roleId = 1L;
        Long permissionId = 1L;

        Permission permission = Permission.builder()
                .permissionId(permissionId)
                .permissionName("TEST_PERMISSION")
                .build();

        Role role = Role.builder()
                .roleId(roleId)
                .roleName("TEST_ROLE")
                .permissions(new HashSet<>(Set.of(permission)))
                .build();

        when(roleRepository.findByIdWithPermissions(roleId)).thenReturn(Optional.of(role));
        when(permissionRepository.findById(permissionId)).thenReturn(Optional.of(permission));
        when(roleRepository.save(any(Role.class))).thenReturn(role);

        // When
        permissionService.removePermissionFromRole(roleId, permissionId);

        // Then
        assertFalse(role.getPermissions().contains(permission));
        verify(roleRepository).findByIdWithPermissions(roleId);
        verify(permissionRepository).findById(permissionId);
        verify(roleRepository).save(role);
    }

    @Test
    @DisplayName("getRolePermissions: returns permissions for role")
    void getRolePermissions() {
        // Given
        Long roleId = 1L;
        Set<Permission> expectedPermissions = Set.of(
                Permission.builder().permissionId(1L).permissionName("PERM1").build(),
                Permission.builder().permissionId(2L).permissionName("PERM2").build()
        );

        Role role = Role.builder()
                .roleId(roleId)
                .permissions(expectedPermissions)
                .build();

        when(roleRepository.findByIdWithPermissions(roleId)).thenReturn(Optional.of(role));

        // When
        Set<Permission> result = permissionService.getRolePermissions(roleId);

        // Then
        assertEquals(expectedPermissions, result);
        verify(roleRepository).findByIdWithPermissions(roleId);
    }

    @Test
    @DisplayName("permissionExists: returns true when permission exists")
    void permissionExists_true() {
        // Given
        String permissionName = "EXISTING_PERMISSION";
        when(permissionRepository.existsByPermissionName(permissionName)).thenReturn(true);

        // When
        boolean result = permissionService.permissionExists(permissionName);

        // Then
        assertTrue(result);
        verify(permissionRepository).existsByPermissionName(permissionName);
    }

    @Test
    @DisplayName("permissionExists: returns false when permission doesn't exist")
    void permissionExists_false() {
        // Given
        String permissionName = "NON_EXISTENT";
        when(permissionRepository.existsByPermissionName(permissionName)).thenReturn(false);

        // When
        boolean result = permissionService.permissionExists(permissionName);

        // Then
        assertFalse(result);
        verify(permissionRepository).existsByPermissionName(permissionName);
    }

    @Test
    @DisplayName("initializePermissions: creates all permissions from enum")
    void initializePermissions() {
        // Given
        when(permissionRepository.existsByPermissionName(any())).thenReturn(false);
        when(permissionRepository.save(any(Permission.class))).thenAnswer(i -> i.getArgument(0));

        // When
        permissionService.initializePermissions();

        // Then
        // Verify all permissions from enum are checked and created
        int expectedPermissionCount = PermissionName.values().length;
        // Each permission is checked twice: once in initializePermissions and once in createPermission
        verify(permissionRepository, times(expectedPermissionCount * 2)).existsByPermissionName(any());
        verify(permissionRepository, times(expectedPermissionCount)).save(any(Permission.class));
    }

    @Test
    @DisplayName("deletePermission: successfully deletes permission and removes from roles")
    void deletePermission_success() {
        // Given
        Long permissionId = 1L;
        Permission permission = Permission.builder()
                .permissionId(permissionId)
                .permissionName("TEST_PERMISSION")
                .roles(new HashSet<>())
                .build();

        Role role1 = Role.builder()
                .roleId(1L)
                .permissions(new HashSet<>(Set.of(permission)))
                .build();

        Role role2 = Role.builder()
                .roleId(2L)
                .permissions(new HashSet<>(Set.of(permission)))
                .build();

        permission.setRoles(Set.of(role1, role2));

        when(permissionRepository.findById(permissionId)).thenReturn(Optional.of(permission));

        // When
        permissionService.deletePermission(permissionId);

        // Then
        assertFalse(role1.getPermissions().contains(permission));
        assertFalse(role2.getPermissions().contains(permission));
        verify(roleRepository, times(2)).save(any(Role.class));
        verify(permissionRepository).delete(permission);
    }

    @Test
    @DisplayName("updatePermission: successfully updates permission")
    void updatePermission_success() {
        // Given
        Long permissionId = 1L;
        String newDescription = "Updated description";
        String newResource = "updated_resource";
        String newAction = "updated_action";

        Permission permission = Permission.builder()
                .permissionId(permissionId)
                .permissionName("TEST_PERMISSION")
                .description("Old description")
                .resource("old_resource")
                .action("old_action")
                .build();

        when(permissionRepository.findById(permissionId)).thenReturn(Optional.of(permission));
        when(permissionRepository.save(any(Permission.class))).thenAnswer(i -> i.getArgument(0));

        // When
        Permission result = permissionService.updatePermission(permissionId, newDescription, newResource, newAction);

        // Then
        assertEquals(newDescription, result.getDescription());
        assertEquals(newResource, result.getResource());
        assertEquals(newAction, result.getAction());
        verify(permissionRepository).findById(permissionId);
        verify(permissionRepository).save(permission);
    }
} 