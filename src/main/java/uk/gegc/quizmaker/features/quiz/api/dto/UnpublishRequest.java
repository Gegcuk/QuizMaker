package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UnpublishRequest", description = "Reason for unpublishing a quiz")
public record UnpublishRequest(
        @Schema(description = "Reason for unpublishing", example = "Policy change") String reason
) {}


