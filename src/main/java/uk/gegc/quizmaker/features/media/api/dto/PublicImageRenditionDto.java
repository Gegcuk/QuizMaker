package uk.gegc.quizmaker.features.media.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Public image rendition suitable for anonymous browsers and social crawlers",
        types = {"object", "null"}, nullable = true,
        example = "{\"url\":\"https://cdn.quizzence.com/media/hero.webp\",\"width\":1200,\"height\":630,\"mimeType\":\"image/webp\"}")
public record PublicImageRenditionDto(
        @Schema(description = "Stable public CDN URL, without credentials or an expiring signature",
                example = "https://cdn.quizzence.com/images/2026/10/04/8b5b6c1a.webp", format = "uri")
        String url,
        @Schema(description = "Image width in pixels; null when historical metadata is unavailable",
                example = "1200", minimum = "1", types = {"integer", "null"}, nullable = true)
        Integer width,
        @Schema(description = "Image height in pixels; null when historical metadata is unavailable",
                example = "630", minimum = "1", types = {"integer", "null"}, nullable = true)
        Integer height,
        @Schema(description = "Image MIME type", example = "image/webp")
        String mimeType
) {
}
