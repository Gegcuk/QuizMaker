package uk.gegc.quizmaker.features.admin.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.admin.domain.model.RolePermissionAudit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RolePermissionAuditRepository extends JpaRepository<RolePermissionAudit, UUID> {
    
    /**
     * Find audit entries for a specific user (as target)
     */
    Page<RolePermissionAudit> findByTargetUser_IdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    
    /**
     * Find audit entries by actor (who performed the action)
     */
    Page<RolePermissionAudit> findByActor_IdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);
    
    /**
     * Find audit entries for a specific role
     */
    Page<RolePermissionAudit> findByRole_RoleIdOrderByCreatedAtDesc(Long roleId, Pageable pageable);
    
    /**
     * Find audit entries by action type
     */
    Page<RolePermissionAudit> findByActionOrderByCreatedAtDesc(RolePermissionAudit.AuditAction action, Pageable pageable);
    
    /**
     * Find audit entries within a time range
     */
    @Query("SELECT rpa FROM RolePermissionAudit rpa WHERE rpa.createdAt BETWEEN :startDate AND :endDate ORDER BY rpa.createdAt DESC")
    Page<RolePermissionAudit> findByCreatedAtBetweenOrderByCreatedAtDesc(
            @Param("startDate") Instant startDate, 
            @Param("endDate") Instant endDate, 
            Pageable pageable);
    
    /**
     * Find audit entries by correlation ID
     */
    List<RolePermissionAudit> findByCorrelationIdOrderByCreatedAtDesc(String correlationId);
    
    /**
     * Find recent audit entries for a user (last 30 days)
     */
    @Query("SELECT rpa FROM RolePermissionAudit rpa WHERE rpa.targetUser.id = :userId AND rpa.createdAt >= :since ORDER BY rpa.createdAt DESC")
    List<RolePermissionAudit> findRecentByTargetUser(@Param("userId") UUID userId, @Param("since") Instant since);
}
