package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationCoverage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface QuizGenerationCoverageRepository extends JpaRepository<QuizGenerationCoverage, UUID> {

    @EntityGraph(attributePaths = "types")
    @Query("SELECT DISTINCT coverage FROM QuizGenerationCoverage coverage WHERE coverage.jobId IN :jobIds")
    List<QuizGenerationCoverage> findAllWithTypesByJobIdIn(@Param("jobIds") Collection<UUID> jobIds);
}
