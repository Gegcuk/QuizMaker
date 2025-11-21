package uk.gegc.quizmaker.features.quizgroup.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembership;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembershipId;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuizGroupMembershipRepository extends JpaRepository<QuizGroupMembership, QuizGroupMembershipId> {

    /**
     * Find all memberships for a group, ordered by position ascending.
     */
    @Query("""
              SELECT m
              FROM QuizGroupMembership m
              WHERE m.group.id = :groupId
              ORDER BY m.position ASC
            """)
    List<QuizGroupMembership> findByGroupIdOrderByPositionAsc(@Param("groupId") UUID groupId);

    @Query(value = """
              SELECT m
              FROM QuizGroupMembership m
              WHERE m.group.id = :groupId
              ORDER BY m.position ASC
            """)
    Page<QuizGroupMembership> findPageByGroupId(@Param("groupId") UUID groupId, Pageable pageable);

    @Query("""
              SELECT m
              FROM QuizGroupMembership m
              WHERE m.group.id IN :groupIds
              ORDER BY m.group.id ASC, m.position ASC
            """)
    List<QuizGroupMembership> findByGroupIdsOrdered(@Param("groupIds") Collection<UUID> groupIds);

    /**
     * Find all groups that contain a specific quiz.
     */
    @Query("""
              SELECT m
              FROM QuizGroupMembership m
              WHERE m.quiz.id = :quizId
            """)
    List<QuizGroupMembership> findByQuizId(@Param("quizId") UUID quizId);

    /**
     * Check if a quiz is already a member of a group (for idempotency).
     */
    @Query("SELECT COUNT(m) > 0 FROM QuizGroupMembership m WHERE m.group.id = :groupId AND m.quiz.id = :quizId")
    boolean existsByGroupIdAndQuizId(@Param("groupId") UUID groupId, @Param("quizId") UUID quizId);

    /**
     * Count memberships for a group.
     */
    @Query("SELECT COUNT(m) FROM QuizGroupMembership m WHERE m.group.id = :groupId")
    long countByGroupId(@Param("groupId") UUID groupId);
}
