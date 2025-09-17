package uk.gegc.quizmaker.features.user.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.user.domain.model.Role;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByRoleName(String roleName);

    boolean existsByRoleName(String roleName);

    Optional<Role> findByIsDefaultTrue();

    /**
     * Find role by name with permissions eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.roleName = :roleName")
    Optional<Role> findByRoleNameWithPermissions(@Param("roleName") String roleName);

    /**
     * Find role by ID with permissions eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.roleId = :roleId")
    Optional<Role> findByIdWithPermissions(@Param("roleId") Long roleId);

    /**
     * Find all roles with permissions eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT DISTINCT r FROM Role r LEFT JOIN FETCH r.permissions")
    List<Role> findAllWithPermissions();

    /**
     * Find default role with permissions eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.isDefault = true")
    Optional<Role> findByIsDefaultTrueWithPermissions();

    /**
     * Find role by ID with users eagerly fetched to avoid N+1 queries
     */
    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.users WHERE r.roleId = :roleId")
    Optional<Role> findByIdWithUsers(@Param("roleId") Long roleId);
}
