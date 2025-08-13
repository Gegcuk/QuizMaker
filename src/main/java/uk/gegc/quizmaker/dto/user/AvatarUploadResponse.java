package uk.gegc.quizmaker.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AvatarUploadResponse", description = "Response returned after uploading a user avatar")
public record AvatarUploadResponse(
        @Schema(description = "Public URL of the uploaded avatar") String avatarUrl,
        @Schema(description = "Human friendly message") String message
) {}


