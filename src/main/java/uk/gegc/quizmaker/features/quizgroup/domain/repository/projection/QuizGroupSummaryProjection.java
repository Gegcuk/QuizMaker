package uk.gegc.quizmaker.features.quizgroup.domain.repository.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight projection for quiz group list views.
 * Prevents N+1 queries by fetching only required fields.
 * <p>
 * IMPORTANT: Located in domain/repository/projection to avoid dependency inversion.
 * Repositories (in domain) can reference projections without breaking architecture.
 */
public interface QuizGroupSummaryProjection {
    UUID getId();
    String getName();
    String getDescription();
    String getColor();
    String getIcon();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    
    // Computed field (from COUNT aggregation)
    Long getQuizCount();
}

