package uk.gegc.quizmaker.features.admin.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.admin.api.dto.CreateRoleRequest;
import uk.gegc.quizmaker.features.admin.api.dto.RoleDto;
import uk.gegc.quizmaker.features.admin.api.dto.UpdateRoleRequest;
import uk.gegc.quizmaker.features.admin.application.PolicyReconciliationService;
import uk.gegc.quizmaker.features.admin.aplication.RoleService;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.security.PermissionUtil;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;

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
    private final PolicyReconciliationService policyReconciliationService;

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

    // Standardized permission-based checking for role assignment
    @PostMapping("/users/{userId}/roles/{roleId}")
    @Operation(summary = "Assign role to user")
    @RequirePermission(PermissionName.ROLE_ASSIGN)
    public ResponseEntity<Void> assignRoleToUser(@PathVariable UUID userId,
                                                 @PathVariable Long roleId) {
        roleService.assignRoleToUser(userId, roleId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @Operation(summary = "Remove role from user")
    @RequirePermission(PermissionName.ROLE_ASSIGN)
    public ResponseEntity<Void> removeRoleFromUser(@PathVariable UUID userId,
                                                   @PathVariable Long roleId) {
        roleService.removeRoleFromUser(userId, roleId);
        return ResponseEntity.ok().build();
    }

    // Standardized annotation-based permission checking
    @PostMapping("/system/initialize")
    @Operation(summary = "Initialize system roles and permissions")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<String> initializeSystem() {
        roleService.initializeDefaultRolesAndPermissions();
        return ResponseEntity.ok("System initialized successfully");
    }

    // Standardized annotation-based permission checking with multiple permissions
    @GetMapping("/system/status")
    @Operation(summary = "Get system status")
    @RequirePermission(value = {PermissionName.SYSTEM_ADMIN, PermissionName.AUDIT_READ},
            operator = RequirePermission.LogicalOperator.OR)
    public ResponseEntity<String> getSystemStatus() {
        // Simplified response - authorization is handled by annotation
        return ResponseEntity.ok("System status: All systems operational");
    }

    // Standardized permission-based checking for super admin operations
    @PostMapping("/super/dangerous-operation")
    @Operation(summary = "Perform dangerous operation")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<String> performDangerousOperation() {
        log.warn("Dangerous operation performed by user: {}",
                permissionUtil.getCurrentUser().getUsername());
        return ResponseEntity.ok("Operation completed");
    }

    // Policy reconciliation endpoints
    @PostMapping("/policy/reconcile")
    @Operation(summary = "Reconcile roles and permissions against canonical manifest")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<PolicyReconciliationService.ReconciliationResult> reconcilePolicy() {
        log.info("Policy reconciliation triggered by user: {}", 
                permissionUtil.getCurrentUser().getUsername());
        
        PolicyReconciliationService.ReconciliationResult result = policyReconciliationService.reconcileAll();
        
        if (result.success()) {
            log.info("Policy reconciliation completed successfully: {}", result.message());
            return ResponseEntity.ok(result);
        } else {
            log.error("Policy reconciliation failed: {}", result.message());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/policy/status")
    @Operation(summary = "Get policy reconciliation status")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<PolicyReconciliationService.PolicyDiff> getPolicyStatus() {
        PolicyReconciliationService.PolicyDiff diff = policyReconciliationService.getPolicyDiff();
        return ResponseEntity.ok(diff);
    }

    @GetMapping("/policy/version")
    @Operation(summary = "Get canonical policy manifest version")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<String> getPolicyVersion() {
        String version = policyReconciliationService.getManifestVersion();
        return ResponseEntity.ok(version);
    }

    @PostMapping("/policy/reconcile/{roleName}")
    @Operation(summary = "Reconcile specific role against canonical manifest")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<PolicyReconciliationService.ReconciliationResult> reconcileRole(@PathVariable String roleName) {
        log.info("Role reconciliation triggered for role: {} by user: {}", 
                roleName, permissionUtil.getCurrentUser().getUsername());
        
        PolicyReconciliationService.ReconciliationResult result = policyReconciliationService.reconcileRole(roleName);
        
        if (result.success()) {
            log.info("Role reconciliation completed successfully for {}: {}", roleName, result.message());
            return ResponseEntity.ok(result);
        } else {
            log.error("Role reconciliation failed for {}: {}", roleName, result.message());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
