package uk.gegc.quizmaker.features.admin.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.admin.domain.model.RolePermissionAudit;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RolePermissionAuditService {
    
    /**
     * Log a role assignment action
     */
    void logRoleAssigned(User actor, User targetUser, Role role, String reason, String correlationId, String ipAddress, String userAgent);
    
    /**
     * Log a role removal action
     */
    void logRoleRemoved(User actor, User targetUser, Role role, String reason, String correlationId, String ipAddress, String userAgent);
    
    /**
     * Log a role creation action
     */
    void logRoleCreated(User actor, Role role, String reason, String correlationId, String ipAddress, String userAgent);
    
    /**
     * Log a role update action
     */
    void logRoleUpdated(User actor, Role role, String beforeState, String afterState, String reason, String correlationId, String ipAddress, String userAgent);
    
    /**
     * Log a role deletion action
     */
    void logRoleDeleted(User actor, Role role, String reason, String correlationId, String ipAddress, String userAgent);
    
    /**
     * Log a policy reconciliation action
     */
    void logPolicyReconciled(User actor, String reason, String correlationId, String ipAddress, String userAgent);
    
    /**
     * Get audit trail for a specific user (as target)
     */
    Page<RolePermissionAudit> getAuditTrailForUser(UUID userId, Pageable pageable);
    
    /**
     * Get audit trail by actor (who performed the action)
     */
    Page<RolePermissionAudit> getAuditTrailByActor(UUID actorId, Pageable pageable);
    
    /**
     * Get audit trail for a specific role
     */
    Page<RolePermissionAudit> getAuditTrailForRole(Long roleId, Pageable pageable);
    
    /**
     * Get audit trail by action type
     */
    Page<RolePermissionAudit> getAuditTrailByAction(RolePermissionAudit.AuditAction action, Pageable pageable);
    
    /**
     * Get audit trail within a time range
     */
    Page<RolePermissionAudit> getAuditTrailByDateRange(Instant startDate, Instant endDate, Pageable pageable);
    
    /**
     * Get audit entries by correlation ID
     */
    List<RolePermissionAudit> getAuditTrailByCorrelationId(String correlationId);
    
    /**
     * Get recent audit entries for a user (last 30 days)
     */
    List<RolePermissionAudit> getRecentAuditTrailForUser(UUID userId);
}
