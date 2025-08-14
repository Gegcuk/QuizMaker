package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ResetPasswordResponse", description = "Response for password reset completion")
public record ResetPasswordResponse(
        @Schema(description = "Success message", example = "Password updated successfully")
        String message
) {}
