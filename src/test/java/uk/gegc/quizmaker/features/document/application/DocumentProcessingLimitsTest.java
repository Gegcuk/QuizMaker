package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Document processing limits configuration")
class DocumentProcessingLimitsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LimitsConfiguration.class);

    @Test
    @DisplayName("Uses bounded PDF memory and scratch defaults")
    void defaultConfigurationUsesBoundedPdfLimits() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            DocumentProcessingLimits limits = context.getBean(DocumentProcessingLimits.class);

            assertThat(limits.getMaxPdfMainMemoryBytes()).isEqualTo(16L * 1024 * 1024);
            assertThat(limits.getMaxPdfStorageBytes()).isEqualTo(512L * 1024 * 1024);
            assertThat(limits.getPdfScratchRetention()).isEqualTo(Duration.ofHours(24));
        });
    }

    @Test
    @DisplayName("Binds custom PDF memory, storage, and retention limits")
    void customConfigurationBindsPdfLimits() {
        contextRunner.withPropertyValues(
                        "quizmaker.document.processing.max-pdf-main-memory-bytes=8388608",
                        "quizmaker.document.processing.max-pdf-storage-bytes=268435456",
                        "quizmaker.document.processing.pdf-scratch-retention=PT2H"
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    DocumentProcessingLimits limits = context.getBean(DocumentProcessingLimits.class);

                    assertThat(limits.getMaxPdfMainMemoryBytes()).isEqualTo(8L * 1024 * 1024);
                    assertThat(limits.getMaxPdfStorageBytes()).isEqualTo(256L * 1024 * 1024);
                    assertThat(limits.getPdfScratchRetention()).isEqualTo(Duration.ofHours(2));
                });
    }

    @Test
    @DisplayName("Rejects PDF storage below the configured main-memory allowance")
    void storageBelowMainMemoryFailsStartup() {
        contextRunner.withPropertyValues(
                        "quizmaker.document.processing.max-pdf-main-memory-bytes=8192",
                        "quizmaker.document.processing.max-pdf-storage-bytes=4096"
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("max-pdf-storage-bytes")
                            .hasStackTraceContaining("max-pdf-main-memory-bytes");
                });
    }

    @Test
    @DisplayName("Rejects a non-positive PDF scratch retention period")
    void nonPositiveScratchRetentionFailsStartup() {
        contextRunner.withPropertyValues(
                        "quizmaker.document.processing.pdf-scratch-retention=PT0S"
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("pdf-scratch-retention must be positive");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentProcessingLimits.class)
    static class LimitsConfiguration {
    }
}
