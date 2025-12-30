package uk.gegc.quizmaker.features.media.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "Where the client should upload the binary payload")
public record UploadTargetDto(
        @Schema(description = "HTTP method", example = "PUT")
        String method,
        @Schema(description = "Presigned URL", example = "https://quizzence.lon1.digitaloceanspaces.com/...signature...")
        String url,
        @Schema(description = "Headers to include in the upload request")
        Map<String, String> headers,
        @Schema(description = "When the presigned URL expires", example = "2025-12-30T14:15:00Z")
        Instant expiresAt
) {
}
