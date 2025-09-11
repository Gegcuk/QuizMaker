package uk.gegc.quizmaker.features.billing.application;

import java.util.UUID;

/**
 * Service for performing reconciliation checks on billing data.
 * Ensures data integrity by verifying that transaction sums match balance totals.
 */
public interface ReconciliationService {

    /**
     * Perform reconciliation for a specific user.
     * 
     * @param userId the user ID to reconcile
     * @return reconciliation result with any drift found
     */
    ReconciliationResult reconcileUser(UUID userId);

    /**
     * Perform reconciliation for all users.
     * 
     * @return summary of reconciliation results
     */
    ReconciliationSummary reconcileAllUsers();

    /**
     * Result of reconciliation for a single user.
     */
    record ReconciliationResult(
            UUID userId,
            boolean isBalanced,
            long calculatedBalance,
            long actualBalance,
            long driftAmount,
            String details
    ) {
        /**
         * Check if there's any drift (should be 0).
         */
        public boolean hasDrift() {
            return driftAmount != 0;
        }
    }

    /**
     * Summary of reconciliation for all users.
     */
    record ReconciliationSummary(
            int totalUsers,
            int balancedUsers,
            int usersWithDrift,
            long totalDriftAmount,
            java.util.List<ReconciliationResult> driftResults
    ) {
        /**
         * Check if reconciliation was successful (no drift).
         */
        public boolean isSuccessful() {
            return usersWithDrift == 0;
        }
    }
}
