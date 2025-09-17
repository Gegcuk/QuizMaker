package uk.gegc.quizmaker.features.admin.application;

import java.util.List;
import java.util.Map;

/**
 * Service for reconciling roles and permissions against the canonical policy manifest.
 * This ensures database state stays in sync with the defined policy.
 */
public interface PolicyReconciliationService {

    /**
     * Reconcile all roles and permissions against the canonical manifest.
     * This will:
     * - Add missing permissions
     * - Add missing roles
     * - Update role-permission mappings to match manifest
     * - Remove obsolete permissions/roles (if configured to do so)
     * 
     * @return ReconciliationResult containing details of changes made
     */
    ReconciliationResult reconcileAll();

    /**
     * Reconcile a specific role against the manifest.
     * 
     * @param roleName The name of the role to reconcile
     * @return ReconciliationResult containing details of changes made
     */
    ReconciliationResult reconcileRole(String roleName);

    /**
     * Get the current policy manifest version.
     * 
     * @return The manifest version
     */
    String getManifestVersion();

    /**
     * Check if the database is in sync with the manifest.
     * 
     * @return true if in sync, false otherwise
     */
    boolean isInSync();

    /**
     * Get a detailed diff between database state and manifest.
     * 
     * @return PolicyDiff containing all differences
     */
    PolicyDiff getPolicyDiff();

    /**
     * Result of a policy reconciliation operation.
     */
    record ReconciliationResult(
        boolean success,
        String message,
        int permissionsAdded,
        int permissionsRemoved,
        int rolesAdded,
        int rolesUpdated,
        int rolePermissionMappingsUpdated,
        List<String> errors
    ) {}

    /**
     * Detailed diff between database state and canonical manifest.
     */
    record PolicyDiff(
        List<String> missingPermissions,
        List<String> extraPermissions,
        List<String> missingRoles,
        List<String> extraRoles,
        Map<String, List<String>> rolePermissionMismatches,
        String manifestVersion,
        boolean isInSync
    ) {}
}
