package uk.gegc.quizmaker.features.quiz.domain.repository.export;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;

import java.util.List;
import java.util.UUID;

/**
 * Repository for quiz export operations.
 * Provides methods that eagerly fetch all necessary relations to avoid N+1 queries.
 */
@Repository
public interface QuizExportRepository extends JpaRepository<Quiz, UUID>, JpaSpecificationExecutor<Quiz> {

    /**
     * Find all quizzes matching the specification with all export-necessary relations eagerly loaded
     */
    @Query("""
        SELECT DISTINCT q
        FROM Quiz q
        LEFT JOIN FETCH q.category
        LEFT JOIN FETCH q.creator
        LEFT JOIN FETCH q.tags
        LEFT JOIN FETCH q.questions questions
        WHERE q.id IN (
            SELECT q2.id FROM Quiz q2
            WHERE q2.isDeleted = false
        )
        ORDER BY q.createdAt DESC
    """)
    List<Quiz> findAllWithCategoryTagsQuestions();

    /**
     * Find all quizzes by IDs with all export-necessary relations eagerly loaded
     */
    @Query("""
        SELECT DISTINCT q
        FROM Quiz q
        LEFT JOIN FETCH q.category
        LEFT JOIN FETCH q.creator
        LEFT JOIN FETCH q.tags
        LEFT JOIN FETCH q.questions
        WHERE q.id IN :ids AND q.isDeleted = false
        ORDER BY q.createdAt DESC
    """)
    List<Quiz> findAllByIdsWithCategoryTagsQuestions(@Param("ids") List<UUID> ids);

    @Override
    @EntityGraph(attributePaths = {"category", "creator", "tags", "questions"})
    List<Quiz> findAll(Specification<Quiz> spec);
}

