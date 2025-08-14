package uk.gegc.quizmaker.service.admin.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.admin.CreateRoleRequest;
import uk.gegc.quizmaker.dto.admin.RoleDto;
import uk.gegc.quizmaker.dto.admin.UpdateRoleRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.features.user.domain.model.*;
import uk.gegc.quizmaker.features.user.infra.mapping.RoleMapper;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.service.admin.PermissionService;
import uk.gegc.quizmaker.service.admin.RoleService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final RoleMapper roleMapper;

    @Override
    public RoleDto createRole(CreateRoleRequest request) {
        if (roleExists(request.getRoleName())) {
            throw new IllegalArgumentException("Role already exists: " + request.getRoleName());
        }

        Role role = Role.builder()
                .roleName(request.getRoleName())
                .description(request.getDescription())
                .isDefault(request.isDefault())
                .permissions(new HashSet<>())
                .build();

        Role savedRole = roleRepository.save(role);
        log.info("Created role: {}", request.getRoleName());
        return roleMapper.toDto(savedRole);
    }

    @Override
    public RoleDto updateRole(Long roleId, UpdateRoleRequest request) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        role.setDescription(request.getDescription());
        role.setDefault(request.isDefault());

        Role updatedRole = roleRepository.save(role);
        log.info("Updated role: {}", role.getRoleName());
        return roleMapper.toDto(updatedRole);
    }

    @Override
    public void deleteRole(Long roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        // Remove role from all users first
        for (User user : role.getUsers()) {
            user.getRoles().remove(role);
            userRepository.save(user);
        }

        roleRepository.delete(role);
        log.info("Deleted role: {}", role.getRoleName());
    }

    @Override
    @Transactional(readOnly = true)
    public RoleDto getRoleById(Long roleId) {
        Role role = roleRepository.findByIdWithPermissions(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        return roleMapper.toDto(role);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleDto> getAllRoles() {
        List<Role> roles = roleRepository.findAllWithPermissions();
        return roles.stream()
                .map(roleMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Role getRoleByName(RoleName roleName) {
        return roleRepository.findByRoleNameWithPermissions(roleName.name())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleName));
    }

    @Override
    public void assignRoleToUser(UUID userId, Long roleId) {
        User user = userRepository.findByIdWithRoles(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        user.getRoles().add(role);
        userRepository.save(user);
        log.info("Assigned role {} to user {}", role.getRoleName(), user.getUsername());
    }

    @Override
    public void removeRoleFromUser(UUID userId, Long roleId) {
        User user = userRepository.findByIdWithRoles(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        user.getRoles().remove(role);
        userRepository.save(user);
        log.info("Removed role {} from user {}", role.getRoleName(), user.getUsername());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Role> getUserRoles(UUID userId) {
        User user = userRepository.findByIdWithRoles(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        return user.getRoles();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean roleExists(String roleName) {
        return roleRepository.existsByRoleName(roleName);
    }

    @Override
    @Transactional(readOnly = true)
    public Role getDefaultRole() {
        return roleRepository.findByIsDefaultTrueWithPermissions()
                .orElseGet(() -> getRoleByName(RoleName.ROLE_USER)); // Fallback to USER role
    }

    @Override
    public void initializeDefaultRolesAndPermissions() {
        log.info("Initializing default roles and permissions");

        // First initialize all permissions
        permissionService.initializePermissions();

        // Create roles with appropriate permissions
        createRoleIfNotExists(RoleName.ROLE_USER.name(), "Basic user role", true, getUserPermissions());
        createRoleIfNotExists(RoleName.ROLE_QUIZ_CREATOR.name(), "Quiz creator role", false, getQuizCreatorPermissions());
        createRoleIfNotExists(RoleName.ROLE_MODERATOR.name(), "Moderator role", false, getModeratorPermissions());
        createRoleIfNotExists(RoleName.ROLE_ADMIN.name(), "Administrator role", false, getAdminPermissions());
        createRoleIfNotExists(RoleName.ROLE_SUPER_ADMIN.name(), "Super administrator role", false, getSuperAdminPermissions());

        log.info("Default roles and permissions initialization completed");
    }

    private void createRoleIfNotExists(String roleName, String description, boolean isDefault, Set<String> permissionNames) {
        if (!roleExists(roleName)) {
            Role role = Role.builder()
                    .roleName(roleName)
                    .description(description)
                    .isDefault(isDefault)
                    .permissions(new HashSet<>())
                    .build();

            Role savedRole = roleRepository.save(role);

            // Assign permissions to role
            for (String permissionName : permissionNames) {
                try {
                    Permission permission = permissionService.getPermissionByName(permissionName);
                    savedRole.getPermissions().add(permission);
                } catch (ResourceNotFoundException e) {
                    log.warn("Permission not found: {}", permissionName);
                }
            }

            roleRepository.save(savedRole);
            log.info("Created role: {} with {} permissions", roleName, savedRole.getPermissions().size());
        }
    }

    private Set<String> getUserPermissions() {
        return Set.of(
                PermissionName.QUIZ_READ.name(),
                PermissionName.QUESTION_READ.name(),
                PermissionName.CATEGORY_READ.name(),
                PermissionName.TAG_READ.name(),
                PermissionName.USER_READ.name(),
                PermissionName.USER_UPDATE.name(),
                PermissionName.USER_DELETE.name(),
                PermissionName.COMMENT_READ.name(),
                PermissionName.COMMENT_CREATE.name(),
                PermissionName.COMMENT_UPDATE.name(),
                PermissionName.COMMENT_DELETE.name(),
                PermissionName.ATTEMPT_CREATE.name(),
                PermissionName.ATTEMPT_READ.name(),
                PermissionName.BOOKMARK_CREATE.name(),
                PermissionName.BOOKMARK_READ.name(),
                PermissionName.BOOKMARK_DELETE.name(),
                PermissionName.FOLLOW_CREATE.name(),
                PermissionName.FOLLOW_DELETE.name(),
                PermissionName.NOTIFICATION_READ.name()
        );
    }

    private Set<String> getQuizCreatorPermissions() {
        Set<String> permissions = new HashSet<>(getUserPermissions());
        permissions.addAll(Set.of(
                PermissionName.QUIZ_CREATE.name(),
                PermissionName.QUIZ_UPDATE.name(),
                PermissionName.QUIZ_DELETE.name(),
                PermissionName.QUIZ_PUBLISH.name(),
                PermissionName.QUESTION_CREATE.name(),
                PermissionName.QUESTION_UPDATE.name(),
                PermissionName.QUESTION_DELETE.name(),
                PermissionName.CATEGORY_CREATE.name(),
                PermissionName.TAG_CREATE.name()
        ));
        return permissions;
    }

    private Set<String> getModeratorPermissions() {
        Set<String> permissions = new HashSet<>(getQuizCreatorPermissions());
        permissions.addAll(Set.of(
                PermissionName.QUIZ_MODERATE.name(),
                PermissionName.QUESTION_MODERATE.name(),
                PermissionName.COMMENT_MODERATE.name(),
                PermissionName.CATEGORY_UPDATE.name(),
                PermissionName.TAG_UPDATE.name(),
                PermissionName.ATTEMPT_READ_ALL.name(),
                PermissionName.USER_MANAGE.name()
        ));
        return permissions;
    }

    private Set<String> getAdminPermissions() {
        Set<String> permissions = new HashSet<>(getModeratorPermissions());
        permissions.addAll(Set.of(
                PermissionName.QUIZ_ADMIN.name(),
                PermissionName.QUESTION_ADMIN.name(),
                PermissionName.CATEGORY_ADMIN.name(),
                PermissionName.TAG_ADMIN.name(),
                PermissionName.USER_ADMIN.name(),
                PermissionName.ROLE_READ.name(),
                PermissionName.ROLE_ASSIGN.name(),
                PermissionName.PERMISSION_READ.name(),
                PermissionName.AUDIT_READ.name(),
                PermissionName.NOTIFICATION_CREATE.name(),
                PermissionName.NOTIFICATION_ADMIN.name(),
                PermissionName.ATTEMPT_DELETE.name()
        ));
        return permissions;
    }

    private Set<String> getSuperAdminPermissions() {
        Set<String> permissions = new HashSet<>(getAdminPermissions());
        permissions.addAll(Set.of(
                PermissionName.SYSTEM_ADMIN.name(),
                PermissionName.ROLE_CREATE.name(),
                PermissionName.ROLE_UPDATE.name(),
                PermissionName.ROLE_DELETE.name(),
                PermissionName.PERMISSION_CREATE.name(),
                PermissionName.PERMISSION_UPDATE.name(),
                PermissionName.PERMISSION_DELETE.name()
        ));
        return permissions;
    }
}
