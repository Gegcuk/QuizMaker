package uk.gegc.quizmaker.features.quiz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

/**
 * Configuration properties for quiz generation job resilience.
 * These properties control timeouts, cleanup scheduling, and rate limits
 * for quiz generation job lifecycle management.
 */
@Data
@Component
@ConfigurationProperties(prefix = "quiz.jobs")
@Validated
public class QuizJobProperties {

    /**
     * Timeout for PENDING jobs to transition to PROCESSING (in minutes).
     * Jobs that don't transition within this window are automatically failed.
     * Default: 2 minutes
     */
    private int pendingActivationTimeoutMinutes = 2;

    /**
     * Fixed delay between cleanup scheduler runs (in seconds).
     * Default: 60 seconds (1 minute)
     */
    private int cleanupFixedDelaySeconds = 60;

    /**
     * Minimum age for a job before it can be cancelled via API (in seconds).
     * Set to 0 to allow immediate cancellation (UI can enforce gating separately).
     * Default: 0 (immediate cancellation allowed)
     */
    private int minCancelAgeSeconds = 0;

    /**
     * Cancellation settings for cost-fairness
     */
    private Cancellation cancellation = new Cancellation();

    /** Durable recovery policy for the short post-AI entitlement transaction. */
    private Finalization finalization = new Finalization();

    @Valid
    private Execution execution = new Execution();

    @Data
    public static class Execution {
        @Min(1)
        private int maxActiveJobs = 4;
        @Min(1)
        private int maxConcurrentChunks = 2;
        @Min(1)
        private int leaseSeconds = 120;
        @Min(1)
        private int heartbeatSeconds = 30;
        @Min(1)
        private int scanSeconds = 10;

        @AssertTrue(message = "execution lease must exceed its heartbeat interval")
        public boolean isLeaseLongerThanHeartbeat() {
            return leaseSeconds > heartbeatSeconds;
        }
    }

    /**
     * Rate limit settings for job starts
     */
    private RateLimitConfig rateLimit = new RateLimitConfig();

    @Data
    public static class Cancellation {
        /**
         * When true, commit tokens on cancel if work has started.
         * Default: true
         */
        private boolean commitOnCancel = true;

        /**
         * Minimum non-refundable tokens committed when the first LLM call starts.
         * Set >0 only if "pay-on-start" policy is desired.
         * Default: 0
         */
        private long minStartFeeTokens = 0;
    }

    @Data
    public static class Finalization {
        /** A FINALIZING marker older than this is treated as interrupted. */
        private int recoveryGraceSeconds = 300;

        /** Fixed delay between recovery scans. */
        private int recoveryFixedDelaySeconds = 60;

        /** Maximum UTF-8 size of one generated-output checkpoint. */
        private int checkpointMaxBytes = 8 * 1024 * 1024;

        /** Maximum candidates read from each recovery class per scan. */
        private int recoveryBatchSize = 50;
    }

    @Data
    public static class RateLimitConfig {
        /**
         * Rate limits for job starts
         */
        private StartLimits start = new StartLimits();

        /**
         * Rate limits for job cancellations
         */
        private CancelLimits cancel = new CancelLimits();

        /**
         * Cooldown imposed after rapid cancels (in minutes).
         * Default: 5 minutes
         */
        private int cooldownOnRapidCancelsMinutes = 5;

        @Data
        public static class StartLimits {
            private int perMinute = 3;
            private int perHour = 15;
            private int perDay = 100;
        }

        @Data
        public static class CancelLimits {
            private int perHour = 5;
            private int perDay = 50;
        }
    }
}
