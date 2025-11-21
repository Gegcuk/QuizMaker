package uk.gegc.quizmaker.features.quizgroup.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroup;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.projection.QuizGroupSummaryProjection;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuizGroupRepository extends JpaRepository<QuizGroup, UUID> {

    /**
     * Find groups by owner with pagination, returning full entities.
     */
    Page<QuizGroup> findByOwnerId(UUID ownerId, Pageable pageable);

    /**
     * Find groups by owner with pagination, returning lightweight projections for list views.
     */
    @Query(value = """
              SELECT g.id as id,
                     g.name as name,
                     g.description as description,
                     g.color as color,
                     g.icon as icon,
                     g.createdAt as createdAt,
                     g.updatedAt as updatedAt,
                     COUNT(m) as quizCount
              FROM QuizGroup g
              LEFT JOIN g.memberships m
              WHERE g.owner.id = :ownerId AND g.isDeleted = false
              GROUP BY g.id, g.name, g.description, g.color, g.icon, g.createdAt, g.updatedAt
            """,
            countQuery = """
              SELECT COUNT(DISTINCT g.id)
              FROM QuizGroup g
              WHERE g.owner.id = :ownerId AND g.isDeleted = false
            """)
    Page<QuizGroupSummaryProjection> findByOwnerIdProjected(@Param("ownerId") UUID ownerId, Pageable pageable);

    /**
     * Find group by document ID (optional, for groups linked to documents).
     */
    Optional<QuizGroup> findByDocumentId(UUID documentId);

    /**
     * Find group with memberships eagerly loaded.
     */
    @EntityGraph(attributePaths = {"memberships", "memberships.quiz"})
    @Query("SELECT g FROM QuizGroup g WHERE g.id = :id AND g.isDeleted = false")
    Optional<QuizGroup> findByIdWithMemberships(@Param("id") UUID id);
}
