package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationProviderUsage;

import java.util.Optional;
import java.util.UUID;

public interface QuizGenerationProviderUsageRepository
        extends JpaRepository<QuizGenerationProviderUsage, UUID> {

    Optional<QuizGenerationProviderUsage> findByJobIdAndProviderAttemptId(
            UUID jobId,
            UUID providerAttemptId
    );

    long countByJobId(UUID jobId);
}
