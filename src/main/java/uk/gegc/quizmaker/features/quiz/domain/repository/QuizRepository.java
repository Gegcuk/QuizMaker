package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, UUID>, JpaSpecificationExecutor<Quiz> {

    @Query("""
              SELECT q
              FROM Quiz q
              LEFT JOIN FETCH q.tags
              WHERE q.id = :id AND q.isDeleted = false
            """)
    Optional<Quiz> findByIdWithTags(@Param("id") UUID id);

    @Query("""
              SELECT q
              FROM Quiz q
              LEFT JOIN FETCH q.questions
              WHERE q.id = :id AND q.isDeleted = false
            """)
    Optional<Quiz> findByIdWithQuestions(@Param("id") UUID id);

    Page<Quiz> findAllByVisibility(Visibility visibility, Pageable pageable);

    Page<Quiz> findAllByVisibilityAndStatus(Visibility visibility, QuizStatus status, Pageable pageable);

    List<Quiz> findAllByStatusOrderByCreatedAtDesc(QuizStatus status);

    List<Quiz> findByCreatorId(UUID creatorId);
}
