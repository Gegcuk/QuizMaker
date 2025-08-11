package uk.gegc.quizmaker.repository.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.quiz.QuizModerationAudit;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuizModerationAuditRepository extends JpaRepository<QuizModerationAudit, UUID> {
    List<QuizModerationAudit> findAllByQuiz_IdOrderByCreatedAtDesc(UUID quizId);
}


