package uk.gegc.quizmaker.repository.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.quiz.Quiz;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, UUID> {

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
}
