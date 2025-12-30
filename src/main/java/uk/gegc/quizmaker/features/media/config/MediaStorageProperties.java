package uk.gegc.quizmaker.features.media.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.media")
public class MediaStorageProperties {

    @NotBlank
    private String bucket = "quizzence";

    @NotBlank
    private String region = "lon1";

    /**
     * S3-compatible API endpoint (prefer region endpoint to avoid bucket duplication in host).
     * Example: https://lon1.digitaloceanspaces.com
     */
    @NotNull
    private URI endpoint = URI.create("https://lon1.digitaloceanspaces.com");

    /**
     * CDN base URL used to render assets publicly.
     * Example: https://cdn.quizzence.com
     */
    @NotBlank
    private String cdnBaseUrl = "https://cdn.quizzence.com";

    /**
     * Lifetime of presigned upload URLs.
     */
    @NotNull
    private Duration uploadUrlTtl = Duration.ofMinutes(15);

    /**
     * Whether to delete objects from storage when assets are deleted in the library.
     */
    private boolean deleteRemote = false;

    /**
     * DO Spaces access key (S3-compatible).
     */
    @NotBlank
    private String accessKey = "dev-do-access-key";

    /**
     * DO Spaces secret key (S3-compatible).
     */
    @NotBlank
    private String secretKey = "dev-do-secret-key";

    @Valid
    @NotNull
    private Limits limits = new Limits();

    @Valid
    @NotNull
    private KeyPrefix keyPrefix = new KeyPrefix();

    @Data
    public static class Limits {
        /**
         * Whitelist of image mime types that can be uploaded.
         */
        private List<String> allowedImageMimeTypes = List.of(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/avif"
        );

        /**
         * Whitelist of document mime types (if enabled later).
         */
        private List<String> allowedDocumentMimeTypes = List.of(
                "application/pdf"
        );

        /**
         * Max size in bytes for images (default 20MB).
         */
        private long maxImageSizeBytes = 20L * 1024 * 1024;

        /**
         * Max size in bytes for documents (default 20MB).
         */
        private long maxDocumentSizeBytes = 20L * 1024 * 1024;
    }

    @Data
    public static class KeyPrefix {
        /**
         * Base folder for article-scoped assets.
         */
        private String articles = "articles";

        /**
         * Base folder for general media library assets.
         */
        private String library = "library";
    }
}
