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
    @DisplayName("Uses bounded parser process defaults for heap, output, termination, and shutdown")
    void defaultConfigurationUsesBoundedParserProcessLimits() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            DocumentProcessingLimits limits = context.getBean(DocumentProcessingLimits.class);

            assertThat(limits.getParserWorkerMaxHeapBytes()).isEqualTo(384L * 1024 * 1024);
            assertThat(limits.getParserWorkerMaxOutputBytes()).isEqualTo(16L * 1024 * 1024);
            assertThat(limits.getParserTerminationGrace()).isEqualTo(Duration.ofSeconds(1));
            assertThat(limits.getParserForceKillTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(limits.getParserShutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    @DisplayName("Binds custom parser process resource and lifecycle limits")
    void customConfigurationBindsParserProcessLimits() {
        contextRunner.withPropertyValues(
                        "quizmaker.document.processing.parser-worker-max-heap-bytes=268435456",
                        "quizmaker.document.processing.parser-worker-max-output-bytes=8388608",
                        "quizmaker.document.processing.parser-termination-grace=PT2S",
                        "quizmaker.document.processing.parser-force-kill-timeout=PT7S",
                        "quizmaker.document.processing.parser-shutdown-timeout=PT12S"
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    DocumentProcessingLimits limits = context.getBean(DocumentProcessingLimits.class);

                    assertThat(limits.getParserWorkerMaxHeapBytes()).isEqualTo(256L * 1024 * 1024);
                    assertThat(limits.getParserWorkerMaxOutputBytes()).isEqualTo(8L * 1024 * 1024);
                    assertThat(limits.getParserTerminationGrace()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(limits.getParserForceKillTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(limits.getParserShutdownTimeout()).isEqualTo(Duration.ofSeconds(12));
                });
    }

    @Test
    @DisplayName("Rejects parser heap below PDF memory or output below extracted text")
    void incompatibleParserResourceBoundsFailStartup() {
        contextRunner.withPropertyValues(
                        "quizmaker.document.processing.max-pdf-main-memory-bytes=134217728",
                        "quizmaker.document.processing.parser-worker-max-heap-bytes=67108864",
                        "quizmaker.document.processing.max-extracted-characters=5000",
                        "quizmaker.document.processing.parser-worker-max-output-bytes=4096"
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("document parser isolation and retention durations must be positive")
                            .hasStackTraceContaining("worker heap must cover PDF main memory")
                            .hasStackTraceContaining("worker output must cover extracted text");
                });
    }

    @Test
    @DisplayName("Rejects a non-positive parser termination duration")
    void nonPositiveParserTerminationDurationFailsStartup() {
        contextRunner.withPropertyValues(
                        "quizmaker.document.processing.parser-termination-grace=PT0S"
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("document parser isolation and retention durations must be positive");
                });
    }

    @Test
    @DisplayName("Rejects a non-positive parser workspace retention period")
    void nonPositiveParserWorkspaceRetentionFailsStartup() {
        contextRunner.withPropertyValues(
                        "quizmaker.document.processing.staging-retention=PT0S"
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("document parser isolation and retention durations must be positive");
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
