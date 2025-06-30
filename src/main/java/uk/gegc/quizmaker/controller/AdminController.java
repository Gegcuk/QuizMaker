package uk.gegc.quizmaker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.admin.CreateRoleRequest;
import uk.gegc.quizmaker.dto.admin.RoleDto;
import uk.gegc.quizmaker.dto.admin.UpdateRoleRequest;
import uk.gegc.quizmaker.model.user.PermissionName;
import uk.gegc.quizmaker.model.user.RoleName;
import uk.gegc.quizmaker.security.PermissionUtil;
import uk.gegc.quizmaker.security.annotation.RequirePermission;
import uk.gegc.quizmaker.security.annotation.RequireRole;
import uk.gegc.quizmaker.service.admin.RoleService;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@SecurityRequirement(name = "Bearer Authentication")
public class AdminController {
    
    private final RoleService roleService;
    private final PermissionUtil permissionUtil;
    
    // Example using annotation-based permission checking
    @GetMapping("/roles")
    @Operation(summary = "Get all roles")
    @RequirePermission(PermissionName.ROLE_READ)
    public ResponseEntity<List<RoleDto>> getAllRoles() {
        List<RoleDto> roles = roleService.getAllRoles();
        return ResponseEntity.ok(roles);
    }
    
    @GetMapping("/roles/{roleId}")
    @Operation(summary = "Get role by ID")
    @RequirePermission(PermissionName.ROLE_READ)
    public ResponseEntity<RoleDto> getRoleById(@PathVariable Long roleId) {
        RoleDto role = roleService.getRoleById(roleId);
        return ResponseEntity.ok(role);
    }
    
    @PostMapping("/roles")
    @Operation(summary = "Create a new role")
    @RequirePermission(PermissionName.ROLE_CREATE)
    public ResponseEntity<RoleDto> createRole(@Valid @RequestBody CreateRoleRequest request) {
        RoleDto createdRole = roleService.createRole(request);
        return ResponseEntity.ok(createdRole);
    }
    
    @PutMapping("/roles/{roleId}")
    @Operation(summary = "Update an existing role")
    @RequirePermission(PermissionName.ROLE_UPDATE)
    public ResponseEntity<RoleDto> updateRole(@PathVariable Long roleId, 
                                              @Valid @RequestBody UpdateRoleRequest request) {
        RoleDto updatedRole = roleService.updateRole(roleId, request);
        return ResponseEntity.ok(updatedRole);
    }
    
    @DeleteMapping("/roles/{roleId}")
    @Operation(summary = "Delete a role")
    @RequirePermission(PermissionName.ROLE_DELETE)
    public ResponseEntity<Void> deleteRole(@PathVariable Long roleId) {
        roleService.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }
    
    // Example using role-based checking
    @PostMapping("/users/{userId}/roles/{roleId}")
    @Operation(summary = "Assign role to user")
    @RequireRole(RoleName.ROLE_ADMIN)
    public ResponseEntity<Void> assignRoleToUser(@PathVariable UUID userId, 
                                                 @PathVariable Long roleId) {
        roleService.assignRoleToUser(userId, roleId);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @Operation(summary = "Remove role from user")
    @RequireRole(RoleName.ROLE_ADMIN)
    public ResponseEntity<Void> removeRoleFromUser(@PathVariable UUID userId, 
                                                   @PathVariable Long roleId) {
        roleService.removeRoleFromUser(userId, roleId);
        return ResponseEntity.ok().build();
    }
    
    // Example using manual permission checking
    @PostMapping("/system/initialize")
    @Operation(summary = "Initialize system roles and permissions")
    public ResponseEntity<String> initializeSystem() {
        // Manual permission check using PermissionUtil
        permissionUtil.requirePermission(PermissionName.SYSTEM_ADMIN);
        
        roleService.initializeDefaultRolesAndPermissions();
        return ResponseEntity.ok("System initialized successfully");
    }
    
    // Example combining multiple permission checks
    @GetMapping("/system/status")
    @Operation(summary = "Get system status")
    @RequirePermission(value = {PermissionName.SYSTEM_ADMIN, PermissionName.AUDIT_READ}, 
                      operator = RequirePermission.LogicalOperator.OR)
    public ResponseEntity<String> getSystemStatus() {
        // Additional manual checks if needed
        if (permissionUtil.isSuperAdmin()) {
            return ResponseEntity.ok("System status: All systems operational (Super Admin view)");
        } else {
            return ResponseEntity.ok("System status: All systems operational (Limited view)");
        }
    }
    
    // Example for super admin only endpoints
    @PostMapping("/super/dangerous-operation")
    @Operation(summary = "Perform dangerous operation")
    @RequireRole(RoleName.ROLE_SUPER_ADMIN)
    public ResponseEntity<String> performDangerousOperation() {
        log.warn("Dangerous operation performed by user: {}", 
                permissionUtil.getCurrentUser().getUsername());
        return ResponseEntity.ok("Operation completed");
    }
}
