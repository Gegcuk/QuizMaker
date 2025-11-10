package uk.gegc.quizmaker.features.admin.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.admin.api.dto.CreateRoleRequest;
import uk.gegc.quizmaker.features.admin.api.dto.RoleDto;
import uk.gegc.quizmaker.features.admin.api.dto.UpdateRoleRequest;
import uk.gegc.quizmaker.features.admin.application.PolicyReconciliationService;
import uk.gegc.quizmaker.features.admin.aplication.RoleService;
import uk.gegc.quizmaker.features.admin.aplication.PermissionService;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.email.EmailService;
import uk.gegc.quizmaker.shared.security.PermissionUtil;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin", description = "System administration, roles, permissions, and policy management")
@SecurityRequirement(name = "Bearer Authentication")
public class AdminController {

    private final RoleService roleService;
    private final PermissionService permissionService;
    private final PermissionUtil permissionUtil;
    private final PolicyReconciliationService policyReconciliationService;
    private final EmailService emailService;

    @Value("${app.admin.system-initialization.enabled:true}")
    private boolean systemInitializationEnabled;

    @GetMapping("/roles")
    @Operation(
            summary = "Get all roles",
            description = "Returns all roles in the system. Requires ROLE_READ permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Roles retrieved",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = RoleDto.class))
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Missing ROLE_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @RequirePermission(PermissionName.ROLE_READ)
    public ResponseEntity<List<RoleDto>> getAllRoles() {
        List<RoleDto> roles = roleService.getAllRoles();
        return ResponseEntity.ok(roles);
    }

    @GetMapping("/roles/paginated")
    @Operation(
            summary = "Get roles with pagination and filtering",
            description = "Returns paginated list of roles with optional search. Requires ROLE_READ permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Roles retrieved",
                    content = @Content(schema = @Schema(implementation = Page.class))
            ),
            @ApiResponse(responseCode = "403", description = "Missing ROLE_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @RequirePermission(PermissionName.ROLE_READ)
    public ResponseEntity<Page<RoleDto>> getAllRolesPaginated(
            @Parameter(description = "Pagination parameters") @PageableDefault(size = 20, sort = "roleName") Pageable pageable,
            @Parameter(description = "Search query for role name") @RequestParam(required = false) String search) {
        Page<RoleDto> roles = roleService.getAllRoles(pageable, search);
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
        // Normalize role name to uppercase
        CreateRoleRequest normalizedRequest = new CreateRoleRequest(
                request.roleName().toUpperCase(),
                request.description(),
                request.isDefault()
        );
        
        RoleDto createdRole = roleService.createRole(normalizedRequest);
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
        if (!systemInitializationEnabled) {
            log.warn("System initialization attempted but disabled via feature flag by user: {}", 
                    permissionUtil.getCurrentUser().getUsername());
            return ResponseEntity.badRequest().body("System initialization is disabled");
        }
        
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

    // Permission CRUD endpoints
    @GetMapping("/permissions")
    @Operation(summary = "Get all permissions")
    @RequirePermission(PermissionName.PERMISSION_READ)
    public ResponseEntity<List<Permission>> getAllPermissions() {
        List<Permission> permissions = permissionService.getAllPermissions();
        return ResponseEntity.ok(permissions);
    }

    @GetMapping("/permissions/{permissionId}")
    @Operation(summary = "Get permission by ID")
    @RequirePermission(PermissionName.PERMISSION_READ)
    public ResponseEntity<Permission> getPermissionById(@PathVariable Long permissionId) {
        Permission permission = permissionService.getPermissionById(permissionId);
        return ResponseEntity.ok(permission);
    }

    @PostMapping("/permissions")
    @Operation(summary = "Create a new permission")
    @RequirePermission(PermissionName.PERMISSION_CREATE)
    public ResponseEntity<Permission> createPermission(@Valid @RequestBody CreatePermissionRequest request) {
        Permission permission = permissionService.createPermission(
            request.permissionName().toUpperCase(), // Normalize to uppercase
            request.description(),
            request.resource(),
            request.action()
        );
        return ResponseEntity.ok(permission);
    }

    @PutMapping("/permissions/{permissionId}")
    @Operation(summary = "Update an existing permission")
    @RequirePermission(PermissionName.PERMISSION_UPDATE)
    public ResponseEntity<Permission> updatePermission(@PathVariable Long permissionId,
                                                      @Valid @RequestBody UpdatePermissionRequest request) {
        Permission permission = permissionService.updatePermission(
            permissionId,
            request.description(),
            request.resource(),
            request.action()
        );
        return ResponseEntity.ok(permission);
    }

    @DeleteMapping("/permissions/{permissionId}")
    @Operation(summary = "Delete a permission")
    @RequirePermission(PermissionName.PERMISSION_DELETE)
    public ResponseEntity<Void> deletePermission(@PathVariable Long permissionId) {
        permissionService.deletePermission(permissionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/roles/{roleId}/permissions/{permissionId}")
    @Operation(summary = "Assign permission to role")
    @RequirePermission(PermissionName.ROLE_ASSIGN)
    public ResponseEntity<Void> assignPermissionToRole(@PathVariable Long roleId,
                                                       @PathVariable Long permissionId) {
        permissionService.assignPermissionToRole(roleId, permissionId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/roles/{roleId}/permissions/{permissionId}")
    @Operation(summary = "Remove permission from role")
    @RequirePermission(PermissionName.ROLE_ASSIGN)
    public ResponseEntity<Void> removePermissionFromRole(@PathVariable Long roleId,
                                                         @PathVariable Long permissionId) {
        permissionService.removePermissionFromRole(roleId, permissionId);
        return ResponseEntity.ok().build();
    }

    // Email testing endpoints for development/admin purposes
    @PostMapping("/email/test-verification")
    @Operation(summary = "Test email verification email sending")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<String> testEmailVerification(@RequestParam String email) {
        try {
            log.info("Admin email test: sending verification email to: {}", email);
            emailService.sendEmailVerificationEmail(email, "test-token-123");
            return ResponseEntity.ok("Email verification test sent successfully to: " + email);
        } catch (Exception e) {
            log.error("Failed to send email verification test to: {}", email, e);
            return ResponseEntity.badRequest().body("Failed to send test email: " + e.getMessage());
        }
    }

    @PostMapping("/email/test-password-reset")
    @Operation(summary = "Test password reset email sending")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<String> testPasswordResetEmail(@RequestParam String email) {
        try {
            log.info("Admin email test: sending password reset email to: {}", email);
            emailService.sendPasswordResetEmail(email, "test-reset-token-456");
            return ResponseEntity.ok("Password reset test sent successfully to: " + email);
        } catch (Exception e) {
            log.error("Failed to send password reset test to: {}", email, e);
            return ResponseEntity.badRequest().body("Failed to send test email: " + e.getMessage());
        }
    }

    @GetMapping("/email/provider-status")
    @Operation(summary = "Get current email provider status and configuration")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<EmailProviderStatus> getEmailProviderStatus() {
        String providerClass = emailService.getClass().getSimpleName();
        boolean isNoop = providerClass.equals("NoopEmailService");
        boolean isSes = providerClass.equals("AwsSesEmailService");
        boolean isSmtp = providerClass.equals("EmailServiceImpl");
        
        return ResponseEntity.ok(new EmailProviderStatus(
                providerClass,
                isNoop,
                isSes,
                isSmtp
        ));
    }

    // DTOs for permission operations
    @Schema(name = "CreatePermissionRequest", description = "Request to create a new permission")
    public record CreatePermissionRequest(
        @Schema(description = "Permission name (uppercase)", example = "QUIZ_CREATE")
        @NotBlank String permissionName,
        
        @Schema(description = "Permission description", example = "Create new quizzes")
        String description,
        
        @Schema(description = "Resource type", example = "QUIZ")
        String resource,
        
        @Schema(description = "Action type", example = "CREATE")
        String action
    ) {}

    @Schema(name = "UpdatePermissionRequest", description = "Request to update an existing permission")
    public record UpdatePermissionRequest(
        @Schema(description = "Updated description")
        String description,
        
        @Schema(description = "Updated resource type")
        String resource,
        
        @Schema(description = "Updated action type")
        String action
    ) {}

    @Schema(name = "EmailProviderStatus", description = "Current email provider configuration")
    public record EmailProviderStatus(
        @Schema(description = "Email provider class name", example = "AwsSesEmailService")
        String providerClass,
        
        @Schema(description = "Whether using no-op provider", example = "false")
        boolean isNoop,
        
        @Schema(description = "Whether using AWS SES", example = "true")
        boolean isSes,
        
        @Schema(description = "Whether using SMTP", example = "false")
        boolean isSmtp
    ) {}
}
