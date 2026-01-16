package uk.gegc.quizmaker.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Resolved media reference for rendering")
public record MediaRefDto(
    @Schema(description = "Media asset identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID assetId,

    @Schema(description = "Resolved CDN URL", example = "https://cdn.example.com/media/3fa85f64.png")
    String cdnUrl,

    @Schema(description = "Alternative text", example = "Diagram of the water cycle")
    String alt,

    @Schema(description = "Caption text", example = "Figure 1: Water cycle")
    String caption,

    @Schema(description = "Image width in pixels", example = "1280")
    Integer width,

    @Schema(description = "Image height in pixels", example = "720")
    Integer height,

    @Schema(description = "Media MIME type", example = "image/png")
    String mimeType
) {}
