package uk.gegc.quizmaker.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.admin.CreateRoleRequest;
import uk.gegc.quizmaker.dto.admin.RoleDto;
import uk.gegc.quizmaker.dto.admin.UpdateRoleRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.RoleMapper;
import uk.gegc.quizmaker.model.user.Permission;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.RoleName;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.PermissionRepository;
import uk.gegc.quizmaker.repository.user.RoleRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.admin.impl.RoleServiceImpl;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PermissionService permissionService;

    @Mock
    private RoleMapper roleMapper;

    @InjectMocks
    private RoleServiceImpl roleService;

    @Test
    @DisplayName("createRole: successfully creates new role")
    void createRole_success() {
        // Given
        CreateRoleRequest request = CreateRoleRequest.builder()
                .roleName("TEST_ROLE")
                .description("Test role description")
                .isDefault(false)
                .build();

        when(roleRepository.existsByRoleName(request.getRoleName())).thenReturn(false);

        Role savedRole = Role.builder()
                .roleId(1L)
                .roleName(request.getRoleName())
                .description(request.getDescription())
                .isDefault(request.isDefault())
                .permissions(new HashSet<>())
                .build();

        RoleDto expectedDto = RoleDto.builder()
                .roleId(1L)
                .roleName(request.getRoleName())
                .description(request.getDescription())
                .isDefault(request.isDefault())
                .permissions(new HashSet<>())
                .build();

        when(roleRepository.save(any(Role.class))).thenReturn(savedRole);
        when(roleMapper.toDto(savedRole)).thenReturn(expectedDto);

        // When
        RoleDto result = roleService.createRole(request);

        // Then
        assertNotNull(result);
        assertEquals(expectedDto, result);
        verify(roleRepository).existsByRoleName(request.getRoleName());
        verify(roleRepository).save(any(Role.class));
        verify(roleMapper).toDto(savedRole);
    }

    @Test
    @DisplayName("createRole: throws exception when role already exists")
    void createRole_alreadyExists() {
        // Given
        CreateRoleRequest request = CreateRoleRequest.builder()
                .roleName("EXISTING_ROLE")
                .build();

        when(roleRepository.existsByRoleName(request.getRoleName())).thenReturn(true);

        // When & Then
        assertThrows(IllegalArgumentException.class, () ->
                roleService.createRole(request)
        );

        verify(roleRepository).existsByRoleName(request.getRoleName());
        verify(roleRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateRole: successfully updates role")
    void updateRole_success() {
        // Given
        Long roleId = 1L;
        UpdateRoleRequest request = UpdateRoleRequest.builder()
                .description("Updated description")
                .isDefault(true)
                .build();

        Role existingRole = Role.builder()
                .roleId(roleId)
                .roleName("TEST_ROLE")
                .description("Old description")
                .isDefault(false)
                .build();

        RoleDto expectedDto = RoleDto.builder()
                .roleId(roleId)
                .roleName("TEST_ROLE")
                .description(request.getDescription())
                .isDefault(request.isDefault())
                .build();

        when(roleRepository.findById(roleId)).thenReturn(Optional.of(existingRole));
        when(roleRepository.save(any(Role.class))).thenReturn(existingRole);
        when(roleMapper.toDto(existingRole)).thenReturn(expectedDto);

        // When
        RoleDto result = roleService.updateRole(roleId, request);

        // Then
        assertEquals(expectedDto, result);
        assertEquals(request.getDescription(), existingRole.getDescription());
        assertEquals(request.isDefault(), existingRole.isDefault());
        verify(roleRepository).findById(roleId);
        verify(roleRepository).save(existingRole);
    }

    @Test
    @DisplayName("updateRole: throws exception when role not found")
    void updateRole_notFound() {
        // Given
        Long roleId = 999L;
        UpdateRoleRequest request = UpdateRoleRequest.builder().build();

        when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(ResourceNotFoundException.class, () ->
                roleService.updateRole(roleId, request)
        );

        verify(roleRepository).findById(roleId);
        verify(roleRepository, never()).save(any());
    }

    @Test
    @DisplayName("deleteRole: successfully deletes role and removes from users")
    void deleteRole_success() {
        // Given
        Long roleId = 1L;
        Role role = Role.builder()
                .roleId(roleId)
                .roleName("TEST_ROLE")
                .users(new HashSet<>())
                .build();

        User user1 = new User();
        user1.setRoles(new HashSet<>(Set.of(role)));

        User user2 = new User();
        user2.setRoles(new HashSet<>(Set.of(role)));

        role.setUsers(Set.of(user1, user2));

        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        // When
        roleService.deleteRole(roleId);

        // Then
        assertFalse(user1.getRoles().contains(role));
        assertFalse(user2.getRoles().contains(role));
        verify(userRepository, times(2)).save(any(User.class));
        verify(roleRepository).delete(role);
    }

    @Test
    @DisplayName("getRoleById: returns role when found")
    void getRoleById_found() {
        // Given
        Long roleId = 1L;
        Role role = Role.builder()
                .roleId(roleId)
                .roleName("TEST_ROLE")
                .build();

        RoleDto expectedDto = RoleDto.builder()
                .roleId(roleId)
                .roleName("TEST_ROLE")
                .build();

        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(roleMapper.toDto(role)).thenReturn(expectedDto);

        // When
        RoleDto result = roleService.getRoleById(roleId);

        // Then
        assertEquals(expectedDto, result);
        verify(roleRepository).findById(roleId);
        verify(roleMapper).toDto(role);
    }

    @Test
    @DisplayName("getAllRoles: returns all roles")
    void getAllRoles() {
        // Given
        List<Role> roles = Arrays.asList(
                Role.builder().roleId(1L).roleName("ROLE1").build(),
                Role.builder().roleId(2L).roleName("ROLE2").build()
        );

        List<RoleDto> expectedDtos = Arrays.asList(
                RoleDto.builder().roleId(1L).roleName("ROLE1").build(),
                RoleDto.builder().roleId(2L).roleName("ROLE2").build()
        );

        when(roleRepository.findAll()).thenReturn(roles);
        when(roleMapper.toDto(roles.get(0))).thenReturn(expectedDtos.get(0));
        when(roleMapper.toDto(roles.get(1))).thenReturn(expectedDtos.get(1));

        // When
        List<RoleDto> result = roleService.getAllRoles();

        // Then
        assertEquals(2, result.size());
        verify(roleRepository).findAll();
    }

    @Test
    @DisplayName("getRoleByName: returns role when found")
    void getRoleByName_found() {
        // Given
        RoleName roleName = RoleName.ROLE_USER;
        Role expectedRole = Role.builder()
                .roleId(1L)
                .roleName(roleName.name())
                .build();

        when(roleRepository.findByRoleName(roleName.name())).thenReturn(Optional.of(expectedRole));

        // When
        Role result = roleService.getRoleByName(roleName);

        // Then
        assertEquals(expectedRole, result);
        verify(roleRepository).findByRoleName(roleName.name());
    }

    @Test
    @DisplayName("assignRoleToUser: successfully assigns role to user")
    void assignRoleToUser_success() {
        // Given
        UUID userId = UUID.randomUUID();
        Long roleId = 1L;

        User user = new User();
        user.setId(userId);
        user.setRoles(new HashSet<>());

        Role role = Role.builder()
                .roleId(roleId)
                .roleName("TEST_ROLE")
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(userRepository.save(any(User.class))).thenReturn(user);

        // When
        roleService.assignRoleToUser(userId, roleId);

        // Then
        assertTrue(user.getRoles().contains(role));
        verify(userRepository).findById(userId);
        verify(roleRepository).findById(roleId);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("removeRoleFromUser: successfully removes role from user")
    void removeRoleFromUser_success() {
        // Given
        UUID userId = UUID.randomUUID();
        Long roleId = 1L;

        Role role = Role.builder()
                .roleId(roleId)
                .roleName("TEST_ROLE")
                .build();

        User user = new User();
        user.setId(userId);
        user.setRoles(new HashSet<>(Set.of(role)));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(userRepository.save(any(User.class))).thenReturn(user);

        // When
        roleService.removeRoleFromUser(userId, roleId);

        // Then
        assertFalse(user.getRoles().contains(role));
        verify(userRepository).findById(userId);
        verify(roleRepository).findById(roleId);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("getUserRoles: returns user's roles")
    void getUserRoles() {
        // Given
        UUID userId = UUID.randomUUID();
        Set<Role> expectedRoles = Set.of(
                Role.builder().roleId(1L).roleName("ROLE1").build(),
                Role.builder().roleId(2L).roleName("ROLE2").build()
        );

        User user = new User();
        user.setId(userId);
        user.setRoles(expectedRoles);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // When
        Set<Role> result = roleService.getUserRoles(userId);

        // Then
        assertEquals(expectedRoles, result);
        verify(userRepository).findById(userId);
    }

    @Test
    @DisplayName("roleExists: returns true when role exists")
    void roleExists_true() {
        // Given
        String roleName = "EXISTING_ROLE";
        when(roleRepository.existsByRoleName(roleName)).thenReturn(true);

        // When
        boolean result = roleService.roleExists(roleName);

        // Then
        assertTrue(result);
        verify(roleRepository).existsByRoleName(roleName);
    }

    @Test
    @DisplayName("getDefaultRole: returns default role when exists")
    void getDefaultRole_exists() {
        // Given
        Role defaultRole = Role.builder()
                .roleId(1L)
                .roleName(RoleName.ROLE_USER.name())
                .isDefault(true)
                .build();

        when(roleRepository.findByIsDefaultTrue()).thenReturn(Optional.of(defaultRole));

        // When
        Role result = roleService.getDefaultRole();

        // Then
        assertEquals(defaultRole, result);
        verify(roleRepository).findByIsDefaultTrue();
        verify(roleRepository, never()).findByRoleName(any());
    }

    @Test
    @DisplayName("getDefaultRole: falls back to ROLE_USER when no default")
    void getDefaultRole_fallback() {
        // Given
        Role userRole = Role.builder()
                .roleId(1L)
                .roleName(RoleName.ROLE_USER.name())
                .build();

        when(roleRepository.findByIsDefaultTrue()).thenReturn(Optional.empty());
        when(roleRepository.findByRoleName(RoleName.ROLE_USER.name())).thenReturn(Optional.of(userRole));

        // When
        Role result = roleService.getDefaultRole();

        // Then
        assertEquals(userRole, result);
        verify(roleRepository).findByIsDefaultTrue();
        verify(roleRepository).findByRoleName(RoleName.ROLE_USER.name());
    }

    @Test
    @DisplayName("initializeDefaultRolesAndPermissions: initializes all default roles")
    void initializeDefaultRolesAndPermissions() {
        // Given
        when(roleRepository.existsByRoleName(any())).thenReturn(false);
        when(roleRepository.save(any(Role.class))).thenAnswer(i -> i.getArgument(0));

        Permission permission = Permission.builder()
                .permissionId(1L)
                .permissionName("TEST_PERMISSION")
                .build();

        when(permissionService.getPermissionByName(any())).thenReturn(permission);

        // When
        roleService.initializeDefaultRolesAndPermissions();

        // Then
        verify(permissionService).initializePermissions();
        // Verify roles are created for each RoleName enum value
        verify(roleRepository, times(RoleName.values().length)).existsByRoleName(any());
        verify(roleRepository, atLeast(RoleName.values().length)).save(any(Role.class));
    }
} 