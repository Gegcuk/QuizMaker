package uk.gegc.quizmaker.service.admin.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.user.Permission;
import uk.gegc.quizmaker.model.user.PermissionName;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.repository.user.PermissionRepository;
import uk.gegc.quizmaker.repository.user.RoleRepository;
import uk.gegc.quizmaker.service.admin.PermissionService;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PermissionServiceImpl implements PermissionService {
    
    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    
    @Override
    public Permission createPermission(String permissionName, String description, String resource, String action) {
        if (permissionExists(permissionName)) {
            throw new IllegalArgumentException("Permission already exists: " + permissionName);
        }
        
        Permission permission = Permission.builder()
                .permissionName(permissionName)
                .description(description)
                .resource(resource)
                .action(action)
                .build();
        
        Permission savedPermission = permissionRepository.save(permission);
        log.info("Created permission: {}", permissionName);
        return savedPermission;
    }
    
    @Override
    @Transactional(readOnly = true)
    public Permission getPermissionByName(String permissionName) {
        return permissionRepository.findByPermissionName(permissionName)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionName));
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<Permission> getAllPermissions() {
        return permissionRepository.findAll();
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<Permission> getPermissionsByResource(String resource) {
        return permissionRepository.findByResource(resource);
    }
    
    @Override
    public void assignPermissionToRole(Long roleId, Long permissionId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));
        
        Permission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionId));
        
        role.getPermissions().add(permission);
        roleRepository.save(role);
        log.info("Assigned permission {} to role {}", permission.getPermissionName(), role.getRoleName());
    }
    
    @Override
    public void removePermissionFromRole(Long roleId, Long permissionId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));
        
        Permission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionId));
        
        role.getPermissions().remove(permission);
        roleRepository.save(role);
        log.info("Removed permission {} from role {}", permission.getPermissionName(), role.getRoleName());
    }
    
    @Override
    @Transactional(readOnly = true)
    public Set<Permission> getRolePermissions(Long roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));
        
        return role.getPermissions();
    }
    
    @Override
    @Transactional(readOnly = true)
    public boolean permissionExists(String permissionName) {
        return permissionRepository.existsByPermissionName(permissionName);
    }
    
    @Override
    public void initializePermissions() {
        log.info("Initializing permissions from PermissionName enum");
        
        for (PermissionName permissionName : PermissionName.values()) {
            if (!permissionExists(permissionName.name())) {
                createPermission(
                        permissionName.name(),
                        permissionName.getDescription(),
                        permissionName.getResource(),
                        permissionName.getAction()
                );
            }
        }
        
        log.info("Permissions initialization completed");
    }
    
    @Override
    public void deletePermission(Long permissionId) {
        Permission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionId));
        
        // Remove permission from all roles first
        for (Role role : permission.getRoles()) {
            role.getPermissions().remove(permission);
            roleRepository.save(role);
        }
        
        permissionRepository.delete(permission);
        log.info("Deleted permission: {}", permission.getPermissionName());
    }
    
    @Override
    public Permission updatePermission(Long permissionId, String description, String resource, String action) {
        Permission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found: " + permissionId));
        
        permission.setDescription(description);
        permission.setResource(resource);
        permission.setAction(action);
        
        Permission updatedPermission = permissionRepository.save(permission);
        log.info("Updated permission: {}", permission.getPermissionName());
        return updatedPermission;
    }
}
