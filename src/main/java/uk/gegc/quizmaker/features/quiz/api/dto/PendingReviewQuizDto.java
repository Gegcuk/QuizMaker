package uk.gegc.quizmaker.features.quiz.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "PendingReviewQuizDto", description = "Summary of a quiz pending moderation review")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingReviewQuizDto(
        @Schema(description = "Quiz UUID") UUID id,
        @Schema(description = "Title") String title,
        @Schema(description = "Creator user UUID") UUID creatorId,
        @Schema(description = "Created timestamp") Instant createdAt
) {
}


