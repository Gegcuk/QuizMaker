package uk.gegc.quizmaker.features.document.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Resource limits for the legacy document upload and conversion pipeline.
 * All values are server-owned; multipart fields never choose a larger limit.
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "quizmaker.document.processing")
public class DocumentProcessingLimits {

    private static final long DEFAULT_MAX_UPLOAD_BYTES = 150L * 1024 * 1024;
    private static final int DEFAULT_MAX_EXTRACTED_CHARACTERS = 1_000_000;
    private static final int DEFAULT_MAX_PDF_PAGES = 1_500;
    private static final int DEFAULT_MAX_EPUB_ENTRIES = 10_000;
    private static final long DEFAULT_MAX_EPUB_UNCOMPRESSED_BYTES = 300L * 1024 * 1024;
    private static final int DEFAULT_MAX_EPUB_COMPRESSION_RATIO = 100;
    private static final int DEFAULT_MAX_CONCURRENT_PARSES = 2;
    private static final int DEFAULT_MAX_CONCURRENT_PARSES_PER_USER = 1;
    private static final Duration DEFAULT_PARSE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_STAGING_RETENTION = Duration.ofHours(24);

    @Min(1)
    private long maxUploadBytes = DEFAULT_MAX_UPLOAD_BYTES;

    @Min(1)
    @Max(100_000_000)
    private int maxExtractedCharacters = DEFAULT_MAX_EXTRACTED_CHARACTERS;

    @Min(1)
    private int maxPdfPages = DEFAULT_MAX_PDF_PAGES;

    @Min(1)
    private int maxEpubEntries = DEFAULT_MAX_EPUB_ENTRIES;

    @Min(1)
    private long maxEpubUncompressedBytes = DEFAULT_MAX_EPUB_UNCOMPRESSED_BYTES;

    @Min(1)
    @Max(10_000)
    private int maxEpubCompressionRatio = DEFAULT_MAX_EPUB_COMPRESSION_RATIO;

    @Min(1)
    @Max(32)
    private int maxConcurrentParses = DEFAULT_MAX_CONCURRENT_PARSES;

    @Min(1)
    @Max(8)
    private int maxConcurrentParsesPerUser = DEFAULT_MAX_CONCURRENT_PARSES_PER_USER;

    private Duration parseTimeout = DEFAULT_PARSE_TIMEOUT;

    private Duration stagingRetention = DEFAULT_STAGING_RETENTION;

    @NotBlank
    private String storageRoot = "uploads/documents";

    public static DocumentProcessingLimits defaults() {
        return new DocumentProcessingLimits();
    }
}
