package uk.gegc.quizmaker.features.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChangePasswordResponse", description = "Response returned after successfully changing a password")
public record ChangePasswordResponse(
        @Schema(description = "Status message", example = "Password updated successfully")
        String message
) {
}
