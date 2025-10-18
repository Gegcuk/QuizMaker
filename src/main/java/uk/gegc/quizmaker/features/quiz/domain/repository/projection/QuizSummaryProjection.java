package uk.gegc.quizmaker.features.quiz.domain.repository.projection;

import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight projection for quiz list views.
 * Prevents N+1 queries by fetching only required fields.
 * <p>
 * IMPORTANT: Located in domain/repository to avoid dependency inversion.
 * Repositories (in domain) can reference projections without breaking architecture.
 */
public interface QuizSummaryProjection {
    UUID getId();
    String getTitle();
    String getDescription();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    QuizStatus getStatus();
    Visibility getVisibility();
    
    // Creator info
    String getCreatorUsername();
    UUID getCreatorId();  // UUID, not String!
    
    // Category info
    String getCategoryName();
    UUID getCategoryId();
    
    // Computed fields (from COUNT aggregation)
    Long getQuestionCount();
    Long getTagCount();
    
    // Matches entity field name: estimatedTime (not estimatedTimeMinutes)
    Integer getEstimatedTime();
}

