package uk.gegc.quizmaker.features.media.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

@Schema(description = "Payload to finalize an upload after the client PUT completes")
public record MediaUploadCompleteRequest(
        @Schema(description = "Pixel width (images only)", example = "1280")
        @Min(1)
        Integer width,

        @Schema(description = "Pixel height (images only)", example = "720")
        @Min(1)
        Integer height,

        @Schema(description = "Optional SHA-256 checksum (hex)", example = "a3b5c6...")
        String sha256
) {
}
