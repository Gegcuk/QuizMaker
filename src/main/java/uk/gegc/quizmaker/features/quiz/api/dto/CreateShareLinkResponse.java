package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CreateShareLinkResponse", description = "Response containing link details and the raw token (only once)")
public record CreateShareLinkResponse(
        @Schema(description = "Share link descriptor") ShareLinkDto link,
        @Schema(description = "Raw token, return only on creation") String token
) {}


