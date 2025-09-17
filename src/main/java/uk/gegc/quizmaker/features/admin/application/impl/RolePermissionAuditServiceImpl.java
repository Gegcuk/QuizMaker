package uk.gegc.quizmaker.features.admin.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.admin.application.RolePermissionAuditService;
import uk.gegc.quizmaker.features.admin.domain.model.RolePermissionAudit;
import uk.gegc.quizmaker.features.admin.domain.repository.RolePermissionAuditRepository;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RolePermissionAuditServiceImpl implements RolePermissionAuditService {

    private final RolePermissionAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void logRoleAssigned(User actor, User targetUser, Role role, String reason, String correlationId, String ipAddress, String userAgent) {
        try {
            RolePermissionAudit audit = new RolePermissionAudit();
            audit.setActor(actor);
            audit.setTargetUser(targetUser);
            audit.setRole(role);
            audit.setAction(RolePermissionAudit.AuditAction.ROLE_ASSIGNED);
            audit.setReason(reason);
            audit.setCorrelationId(correlationId);
            audit.setIpAddress(ipAddress);
            audit.setUserAgent(userAgent);
            audit.setCreatedAt(Instant.now());
            
            auditRepository.save(audit);
            log.info("Audit logged: Role {} assigned to user {} by actor {}", 
                    role.getRoleName(), targetUser.getUsername(), actor.getUsername());
        } catch (Exception e) {
            log.error("Failed to log role assignment audit: {}", e.getMessage(), e);
            // Don't throw exception to avoid breaking the main operation
        }
    }

    @Override
    public void logRoleRemoved(User actor, User targetUser, Role role, String reason, String correlationId, String ipAddress, String userAgent) {
        try {
            RolePermissionAudit audit = new RolePermissionAudit();
            audit.setActor(actor);
            audit.setTargetUser(targetUser);
            audit.setRole(role);
            audit.setAction(RolePermissionAudit.AuditAction.ROLE_REMOVED);
            audit.setReason(reason);
            audit.setCorrelationId(correlationId);
            audit.setIpAddress(ipAddress);
            audit.setUserAgent(userAgent);
            audit.setCreatedAt(Instant.now());
            
            auditRepository.save(audit);
            log.info("Audit logged: Role {} removed from user {} by actor {}", 
                    role.getRoleName(), targetUser.getUsername(), actor.getUsername());
        } catch (Exception e) {
            log.error("Failed to log role removal audit: {}", e.getMessage(), e);
            // Don't throw exception to avoid breaking the main operation
        }
    }

    @Override
    public void logRoleCreated(User actor, Role role, String reason, String correlationId, String ipAddress, String userAgent) {
        try {
            RolePermissionAudit audit = new RolePermissionAudit();
            audit.setActor(actor);
            audit.setRole(role);
            audit.setAction(RolePermissionAudit.AuditAction.ROLE_CREATED);
            audit.setReason(reason);
            audit.setCorrelationId(correlationId);
            audit.setIpAddress(ipAddress);
            audit.setUserAgent(userAgent);
            audit.setCreatedAt(Instant.now());
            
            auditRepository.save(audit);
            log.info("Audit logged: Role {} created by actor {}", role.getRoleName(), actor.getUsername());
        } catch (Exception e) {
            log.error("Failed to log role creation audit: {}", e.getMessage(), e);
            // Don't throw exception to avoid breaking the main operation
        }
    }

    @Override
    public void logRoleUpdated(User actor, Role role, String beforeState, String afterState, String reason, String correlationId, String ipAddress, String userAgent) {
        try {
            RolePermissionAudit audit = new RolePermissionAudit();
            audit.setActor(actor);
            audit.setRole(role);
            audit.setAction(RolePermissionAudit.AuditAction.ROLE_UPDATED);
            audit.setReason(reason);
            audit.setBeforeState(beforeState);
            audit.setAfterState(afterState);
            audit.setCorrelationId(correlationId);
            audit.setIpAddress(ipAddress);
            audit.setUserAgent(userAgent);
            audit.setCreatedAt(Instant.now());
            
            auditRepository.save(audit);
            log.info("Audit logged: Role {} updated by actor {}", role.getRoleName(), actor.getUsername());
        } catch (Exception e) {
            log.error("Failed to log role update audit: {}", e.getMessage(), e);
            // Don't throw exception to avoid breaking the main operation
        }
    }

    @Override
    public void logRoleDeleted(User actor, Role role, String reason, String correlationId, String ipAddress, String userAgent) {
        try {
            RolePermissionAudit audit = new RolePermissionAudit();
            audit.setActor(actor);
            audit.setRole(role);
            audit.setAction(RolePermissionAudit.AuditAction.ROLE_DELETED);
            audit.setReason(reason);
            audit.setCorrelationId(correlationId);
            audit.setIpAddress(ipAddress);
            audit.setUserAgent(userAgent);
            audit.setCreatedAt(Instant.now());
            
            auditRepository.save(audit);
            log.info("Audit logged: Role {} deleted by actor {}", role.getRoleName(), actor.getUsername());
        } catch (Exception e) {
            log.error("Failed to log role deletion audit: {}", e.getMessage(), e);
            // Don't throw exception to avoid breaking the main operation
        }
    }

    @Override
    public void logPolicyReconciled(User actor, String reason, String correlationId, String ipAddress, String userAgent) {
        try {
            RolePermissionAudit audit = new RolePermissionAudit();
            audit.setActor(actor);
            audit.setAction(RolePermissionAudit.AuditAction.POLICY_RECONCILED);
            audit.setReason(reason);
            audit.setCorrelationId(correlationId);
            audit.setIpAddress(ipAddress);
            audit.setUserAgent(userAgent);
            audit.setCreatedAt(Instant.now());
            
            auditRepository.save(audit);
            log.info("Audit logged: Policy reconciled by actor {}", actor.getUsername());
        } catch (Exception e) {
            log.error("Failed to log policy reconciliation audit: {}", e.getMessage(), e);
            // Don't throw exception to avoid breaking the main operation
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RolePermissionAudit> getAuditTrailForUser(UUID userId, Pageable pageable) {
        return auditRepository.findByTargetUser_IdOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RolePermissionAudit> getAuditTrailByActor(UUID actorId, Pageable pageable) {
        return auditRepository.findByActor_IdOrderByCreatedAtDesc(actorId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RolePermissionAudit> getAuditTrailForRole(Long roleId, Pageable pageable) {
        return auditRepository.findByRole_RoleIdOrderByCreatedAtDesc(roleId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RolePermissionAudit> getAuditTrailByAction(RolePermissionAudit.AuditAction action, Pageable pageable) {
        return auditRepository.findByActionOrderByCreatedAtDesc(action, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RolePermissionAudit> getAuditTrailByDateRange(Instant startDate, Instant endDate, Pageable pageable) {
        return auditRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RolePermissionAudit> getAuditTrailByCorrelationId(String correlationId) {
        return auditRepository.findByCorrelationIdOrderByCreatedAtDesc(correlationId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RolePermissionAudit> getRecentAuditTrailForUser(UUID userId) {
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        return auditRepository.findRecentByTargetUser(userId, thirtyDaysAgo);
    }

    /**
     * Helper method to serialize role state for audit trail
     */
    private String serializeRoleState(Role role) {
        try {
            return objectMapper.writeValueAsString(role);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize role state for audit: {}", e.getMessage());
            return "Serialization failed: " + role.getRoleName();
        }
    }
}
