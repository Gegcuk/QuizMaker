package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizModerationAudit;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuizModerationAuditRepository extends JpaRepository<QuizModerationAudit, UUID> {
    List<QuizModerationAudit> findAllByQuiz_IdOrderByCreatedAtDesc(UUID quizId);
}


