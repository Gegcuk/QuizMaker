package uk.gegc.quizmaker.features.admin.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.admin.application.PolicyReconciliationService;
import uk.gegc.quizmaker.features.admin.aplication.PermissionService;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.shared.util.XssSanitizer;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PolicyReconciliationServiceImpl implements PolicyReconciliationService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final XssSanitizer xssSanitizer;

    private static final String MANIFEST_PATH = "policy/role-permission-manifest.json";

    @Override
    public ReconciliationResult reconcileAll() {
        log.info("Starting full policy reconciliation");
        List<String> errors = new ArrayList<>();
        int permissionsAdded = 0;
        int permissionsRemoved = 0;
        int rolesAdded = 0;
        int rolesUpdated = 0;
        int rolePermissionMappingsUpdated = 0;

        try {
            JsonNode manifest = loadManifest();
            JsonNode rolesNode = manifest.get("roles");

            // Reconcile permissions first
            permissionsAdded = reconcilePermissions(manifest, errors);

            // Reconcile roles
            for (JsonNode roleNode : rolesNode) {
                String roleName = roleNode.get("name").asText();
                try {
                    ReconciliationResult roleResult = reconcileRole(roleName);
                    rolesAdded += roleResult.rolesAdded();
                    rolesUpdated += roleResult.rolesUpdated();
                    rolePermissionMappingsUpdated += roleResult.rolePermissionMappingsUpdated();
                    errors.addAll(roleResult.errors());
                } catch (Exception e) {
                    log.error("Failed to reconcile role {}: {}", roleName, e.getMessage());
                    // Sanitize user input when including it in error messages
                    String sanitizedRoleName = xssSanitizer.sanitize(roleName);
                    String sanitizedErrorMessage = xssSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : "Unknown error");
                    errors.add("Failed to reconcile role " + sanitizedRoleName + ": " + sanitizedErrorMessage);
                }
            }

            boolean success = errors.isEmpty();
            String message = success 
                ? "Policy reconciliation completed successfully"
                : "Policy reconciliation completed with " + errors.size() + " errors";

            log.info("Policy reconciliation completed: {} permissions added, {} roles added/updated, {} mappings updated",
                permissionsAdded, rolesAdded + rolesUpdated, rolePermissionMappingsUpdated);

            return new ReconciliationResult(success, message, permissionsAdded, permissionsRemoved,
                rolesAdded, rolesUpdated, rolePermissionMappingsUpdated, errors);

        } catch (Exception e) {
            log.error("Failed to reconcile policy: {}", e.getMessage(), e);
            errors.add("Failed to reconcile policy: " + e.getMessage());
            return new ReconciliationResult(false, "Policy reconciliation failed", 0, 0, 0, 0, 0, errors);
        }
    }

    @Override
    public ReconciliationResult reconcileRole(String roleName) {
        log.info("Reconciling role: {}", roleName);
        List<String> errors = new ArrayList<>();
        int rolesAdded = 0;
        int rolesUpdated = 0;
        int rolePermissionMappingsUpdated = 0;

        try {
            JsonNode manifest = loadManifest();
            JsonNode roleNode = manifest.get("roles").get(roleName);
            
            if (roleNode == null) {
                throw new IllegalArgumentException("Role not found in manifest: " + roleName);
            }

            // Check if role exists in database
            Optional<Role> existingRole = roleRepository.findByRoleNameWithPermissions(roleName);
            
            if (existingRole.isEmpty()) {
                // Create new role
                createRoleFromManifest(roleNode);
                rolesAdded = 1;
                log.info("Created new role: {}", roleName);
            } else {
                // Update existing role
                Role role = existingRole.get();
                boolean roleUpdated = updateRoleFromManifest(role, roleNode);
                if (roleUpdated) {
                    rolesUpdated = 1;
                    log.info("Updated existing role: {}", roleName);
                }
            }

            // Update role-permission mappings
            rolePermissionMappingsUpdated = updateRolePermissions(roleName, roleNode, errors);

            return new ReconciliationResult(true, "Role reconciliation completed", 0, 0, 
                rolesAdded, rolesUpdated, rolePermissionMappingsUpdated, errors);

        } catch (Exception e) {
            log.error("Failed to reconcile role {}: {}", roleName, e.getMessage(), e);
            // Sanitize user input (roleName) when including it in error messages to prevent XSS
            String sanitizedRoleName = xssSanitizer.sanitize(roleName);
            String sanitizedErrorMessage = xssSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : "Unknown error");
            errors.add("Failed to reconcile role " + sanitizedRoleName + ": " + sanitizedErrorMessage);
            return new ReconciliationResult(false, "Role reconciliation failed", 0, 0, 0, 0, 0, errors);
        }
    }

    @Override
    public String getManifestVersion() {
        try {
            JsonNode manifest = loadManifest();
            return manifest.get("version").asText();
        } catch (Exception e) {
            log.error("Failed to get manifest version: {}", e.getMessage());
            return "unknown";
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isInSync() {
        try {
            PolicyDiff diff = getPolicyDiff();
            return diff.isInSync();
        } catch (Exception e) {
            log.error("Failed to check sync status: {}", e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyDiff getPolicyDiff() {
        try {
            JsonNode manifest = loadManifest();
            JsonNode rolesNode = manifest.get("roles");

            // Get all permissions from manifest
            Set<String> manifestPermissions = new HashSet<>();
            for (JsonNode roleNode : rolesNode) {
                JsonNode permissionsNode = roleNode.get("permissions");
                for (JsonNode permissionNode : permissionsNode) {
                    manifestPermissions.add(permissionNode.asText());
                }
            }

            // Get all roles from manifest
            Set<String> manifestRoles = new HashSet<>();
            rolesNode.fieldNames().forEachRemaining(manifestRoles::add);

            // Get current database state
            List<Permission> dbPermissions = permissionRepository.findAll();
            List<Role> dbRoles = roleRepository.findAllWithPermissions();

            Set<String> dbPermissionNames = dbPermissions.stream()
                .map(Permission::getPermissionName)
                .collect(Collectors.toSet());

            Set<String> dbRoleNames = dbRoles.stream()
                .map(Role::getRoleName)
                .collect(Collectors.toSet());

            // Find differences
            List<String> missingPermissions = manifestPermissions.stream()
                .filter(p -> !dbPermissionNames.contains(p))
                .collect(Collectors.toList());

            List<String> extraPermissions = dbPermissionNames.stream()
                .filter(p -> !manifestPermissions.contains(p))
                .collect(Collectors.toList());

            List<String> missingRoles = manifestRoles.stream()
                .filter(r -> !dbRoleNames.contains(r))
                .collect(Collectors.toList());

            List<String> extraRoles = dbRoleNames.stream()
                .filter(r -> !manifestRoles.contains(r))
                .collect(Collectors.toList());

            // Check role-permission mappings
            Map<String, List<String>> rolePermissionMismatches = new HashMap<>();
            for (Role role : dbRoles) {
                if (manifestRoles.contains(role.getRoleName())) {
                    JsonNode roleNode = rolesNode.get(role.getRoleName());
                    JsonNode expectedPermissionsNode = roleNode.get("permissions");
                    
                    Set<String> expectedPermissions = new HashSet<>();
                    for (JsonNode permissionNode : expectedPermissionsNode) {
                        expectedPermissions.add(permissionNode.asText());
                    }

                    Set<String> actualPermissions = role.getPermissions().stream()
                        .map(Permission::getPermissionName)
                        .collect(Collectors.toSet());

                    List<String> missing = expectedPermissions.stream()
                        .filter(p -> !actualPermissions.contains(p))
                        .collect(Collectors.toList());

                    List<String> extra = actualPermissions.stream()
                        .filter(p -> !expectedPermissions.contains(p))
                        .collect(Collectors.toList());

                    if (!missing.isEmpty() || !extra.isEmpty()) {
                        List<String> mismatches = new ArrayList<>();
                        if (!missing.isEmpty()) {
                            mismatches.add("Missing: " + missing);
                        }
                        if (!extra.isEmpty()) {
                            mismatches.add("Extra: " + extra);
                        }
                        rolePermissionMismatches.put(role.getRoleName(), mismatches);
                    }
                }
            }

            boolean isInSync = missingPermissions.isEmpty() && extraPermissions.isEmpty() 
                && missingRoles.isEmpty() && extraRoles.isEmpty() && rolePermissionMismatches.isEmpty();

            return new PolicyDiff(missingPermissions, extraPermissions, missingRoles, extraRoles,
                rolePermissionMismatches, getManifestVersion(), isInSync);

        } catch (Exception e) {
            log.error("Failed to get policy diff: {}", e.getMessage(), e);
            return new PolicyDiff(List.of(), List.of(), List.of(), List.of(), Map.of(), "unknown", false);
        }
    }

    private JsonNode loadManifest() throws IOException {
        ClassPathResource resource = new ClassPathResource(MANIFEST_PATH);
        return objectMapper.readTree(resource.getInputStream());
    }

    private int reconcilePermissions(JsonNode manifest, List<String> errors) {
        int permissionsAdded = 0;
        
        // Get all unique permissions from manifest
        Set<String> manifestPermissions = new HashSet<>();
        JsonNode rolesNode = manifest.get("roles");
        for (JsonNode roleNode : rolesNode) {
            JsonNode permissionsNode = roleNode.get("permissions");
            for (JsonNode permissionNode : permissionsNode) {
                manifestPermissions.add(permissionNode.asText());
            }
        }

        // Add missing permissions
        for (String permissionName : manifestPermissions) {
            if (!permissionService.permissionExists(permissionName)) {
                try {
                    // Get permission details from PermissionName enum
                    uk.gegc.quizmaker.features.user.domain.model.PermissionName permEnum = 
                        uk.gegc.quizmaker.features.user.domain.model.PermissionName.valueOf(permissionName);
                    
                    permissionService.createPermission(
                        permissionName,
                        permEnum.getDescription(),
                        permEnum.getResource(),
                        permEnum.getAction()
                    );
                    permissionsAdded++;
                    log.info("Added missing permission: {}", permissionName);
                } catch (Exception e) {
                    log.error("Failed to create permission {}: {}", permissionName, e.getMessage());
                    // Sanitize permission name when including it in error messages
                    String sanitizedPermissionName = xssSanitizer.sanitize(permissionName);
                    String sanitizedErrorMessage = xssSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : "Unknown error");
                    errors.add("Failed to create permission " + sanitizedPermissionName + ": " + sanitizedErrorMessage);
                }
            }
        }

        return permissionsAdded;
    }

    private void createRoleFromManifest(JsonNode roleNode) {
        String roleName = roleNode.get("name").asText();
        String description = roleNode.get("description").asText();
        boolean isDefault = roleNode.get("isDefault").asBoolean();

        Role role = Role.builder()
            .roleName(roleName)
            .description(description)
            .isDefault(isDefault)
            .permissions(new HashSet<>())
            .build();

        roleRepository.save(role);
        log.info("Created role: {}", roleName);
    }

    private boolean updateRoleFromManifest(Role role, JsonNode roleNode) {
        boolean updated = false;
        
        String expectedDescription = roleNode.get("description").asText();
        boolean expectedIsDefault = roleNode.get("isDefault").asBoolean();

        if (!expectedDescription.equals(role.getDescription())) {
            role.setDescription(expectedDescription);
            updated = true;
        }

        if (expectedIsDefault != role.isDefault()) {
            role.setDefault(expectedIsDefault);
            updated = true;
        }

        if (updated) {
            roleRepository.save(role);
        }

        return updated;
    }

    private int updateRolePermissions(String roleName, JsonNode roleNode, List<String> errors) {
        Role role = roleRepository.findByRoleNameWithPermissions(roleName)
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));

        JsonNode expectedPermissionsNode = roleNode.get("permissions");
        Set<String> expectedPermissions = new HashSet<>();
        for (JsonNode permissionNode : expectedPermissionsNode) {
            expectedPermissions.add(permissionNode.asText());
        }

        Set<String> currentPermissions = role.getPermissions().stream()
            .map(Permission::getPermissionName)
            .collect(Collectors.toSet());

        // Add missing permissions
        for (String permissionName : expectedPermissions) {
            if (!currentPermissions.contains(permissionName)) {
                try {
                    Permission permission = permissionService.getPermissionByName(permissionName);
                    role.getPermissions().add(permission);
                    log.info("Added permission {} to role {}", permissionName, roleName);
                } catch (Exception e) {
                    log.error("Failed to add permission {} to role {}: {}", permissionName, roleName, e.getMessage());
                    // Sanitize user input when including it in error messages
                    String sanitizedPermissionName = xssSanitizer.sanitize(permissionName);
                    String sanitizedRoleName = xssSanitizer.sanitize(roleName);
                    String sanitizedErrorMessage = xssSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : "Unknown error");
                    errors.add("Failed to add permission " + sanitizedPermissionName + " to role " + sanitizedRoleName + ": " + sanitizedErrorMessage);
                }
            }
        }

        // Remove extra permissions (optional - could be configured)
        Set<String> permissionsToRemove = currentPermissions.stream()
            .filter(p -> !expectedPermissions.contains(p))
            .collect(Collectors.toSet());

        for (String permissionName : permissionsToRemove) {
            try {
                Permission permission = permissionService.getPermissionByName(permissionName);
                role.getPermissions().remove(permission);
                log.info("Removed permission {} from role {}", permissionName, roleName);
            } catch (Exception e) {
                log.error("Failed to remove permission {} from role {}: {}", permissionName, roleName, e.getMessage());
                // Sanitize user input when including it in error messages
                String sanitizedPermissionName = xssSanitizer.sanitize(permissionName);
                String sanitizedRoleName = xssSanitizer.sanitize(roleName);
                String sanitizedErrorMessage = xssSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : "Unknown error");
                errors.add("Failed to remove permission " + sanitizedPermissionName + " from role " + sanitizedRoleName + ": " + sanitizedErrorMessage);
            }
        }

        if (!expectedPermissions.equals(currentPermissions)) {
            roleRepository.save(role);
            return 1; // One role's permissions updated
        }

        return 0;
    }
}
