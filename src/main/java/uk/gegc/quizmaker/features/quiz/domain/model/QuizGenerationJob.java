package uk.gegc.quizmaker.features.quiz.domain.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "quiz_generation_jobs")
@Data
@NoArgsConstructor
public class QuizGenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "username", referencedColumnName = "username", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GenerationStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "total_chunks")
    private Integer totalChunks;

    @Column(name = "processed_chunks")
    private Integer processedChunks = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "generated_quiz_id")
    private UUID generatedQuizId;

    @Column(name = "request_data", columnDefinition = "JSON")
    private String requestData;

    @Column(name = "estimated_completion")
    private LocalDateTime estimatedCompletion;

    @Column(name = "progress_percentage")
    private Double progressPercentage = 0.0;

    @Column(name = "current_chunk")
    private String currentChunk;

    @Column(name = "total_questions_generated")
    private Integer totalQuestionsGenerated = 0;

    @Column(name = "generation_time_seconds")
    private Long generationTimeSeconds;

    // Billing fields for token consumption tracking
    @Column(name = "billing_reservation_id")
    private UUID billingReservationId;

    @Column(name = "reservation_expires_at")
    private LocalDateTime reservationExpiresAt;

    @Column(name = "billing_estimated_tokens", nullable = false)
    private Long billingEstimatedTokens = 0L;

    @Column(name = "billing_committed_tokens", nullable = false)
    private Long billingCommittedTokens = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_state", nullable = false)
    private BillingState billingState = BillingState.NONE;

    @Column(name = "billing_idempotency_keys", columnDefinition = "JSON")
    private String billingIdempotencyKeys;

    @Column(name = "last_billing_error", columnDefinition = "JSON")
    private String lastBillingError;

    @Column(name = "input_prompt_tokens")
    private Long inputPromptTokens;

    @Column(name = "estimation_version")
    private String estimationVersion;

    @Column(name = "actual_tokens")
    private Long actualTokens;

    @Column(name = "was_capped")
    private Boolean wasCappedAtReserved = false;

    @Column(name = "has_started_ai_calls")
    private Boolean hasStartedAiCalls = false;

    @Column(name = "first_ai_call_at")
    private LocalDateTime firstAiCallAt;

    @Column(name = "total_tasks")
    private Integer totalTasks;

    @Column(name = "completed_tasks")
    private Integer completedTasks = 0;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = GenerationStatus.PENDING;
        }
        if (processedChunks == null) {
            processedChunks = 0;
        }
        if (progressPercentage == null) {
            progressPercentage = 0.0;
        }
        if (totalQuestionsGenerated == null) {
            totalQuestionsGenerated = 0;
        }
        if (billingEstimatedTokens == null) {
            billingEstimatedTokens = 0L;
        }
        if (billingCommittedTokens == null) {
            billingCommittedTokens = 0L;
        }
        if (billingState == null) {
            billingState = BillingState.NONE;
        }
        if (completedTasks == null) {
            completedTasks = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (status == GenerationStatus.COMPLETED || status == GenerationStatus.FAILED) {
            if (completedAt == null) {
                completedAt = LocalDateTime.now();
            }
            if (generationTimeSeconds == null && startedAt != null) {
                generationTimeSeconds = Duration.between(startedAt, completedAt).getSeconds();
            }
        }

        // Update progress percentage - prefer task counters when available
        if (totalTasks != null && totalTasks > 0) {
            progressPercentage = (double) completedTasks / totalTasks * 100.0;
        } else if (totalChunks != null && totalChunks > 0) {
            progressPercentage = (double) processedChunks / totalChunks * 100.0;
        }
    }

    /**
     * Update progress for the generation job (chunk-level)
     */
    public void updateProgress(int processedChunks, String currentChunk) {
        this.processedChunks = processedChunks;
        this.currentChunk = currentChunk;

        // Compute progress percentage using task counters when available
        if (totalTasks != null && totalTasks > 0) {
            this.progressPercentage = (double) completedTasks / totalTasks * 100.0;
        } else if (totalChunks != null && totalChunks > 0) {
            this.progressPercentage = (double) processedChunks / totalChunks * 100.0;
        }
    }

    /**
     * Update task progress by incrementing completed tasks
     * @param completedDelta Number of tasks completed (usually 1)
     * @param statusMessage Human-readable status message (e.g., "Chunk 1/4 · MCQ_SINGLE · done")
     */
    public void updateTaskProgressIncrement(int completedDelta, String statusMessage) {
        this.completedTasks = (this.completedTasks != null ? this.completedTasks : 0) + completedDelta;
        this.currentChunk = statusMessage;

        // Compute progress percentage using task counters
        if (totalTasks != null && totalTasks > 0) {
            this.progressPercentage = (double) completedTasks / totalTasks * 100.0;
        }
    }

    /**
     * Mark the job as completed successfully
     */
    public void markCompleted(UUID generatedQuizId, int totalQuestions) {
        this.status = GenerationStatus.COMPLETED;
        this.generatedQuizId = generatedQuizId;
        this.totalQuestionsGenerated = totalQuestions;
        this.completedAt = LocalDateTime.now();
        this.progressPercentage = 100.0;
        
        // Ensure completedTasks equals totalTasks when available
        if (this.totalTasks != null) {
            this.completedTasks = this.totalTasks;
        }
        // Ensure processedChunks equals totalChunks to maintain consistency
        if (this.totalChunks != null) {
            this.processedChunks = this.totalChunks;
        }
    }

    /**
     * Mark the job as failed
     */
    public void markFailed(String errorMessage) {
        this.status = GenerationStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Check if the job is in a terminal state
     */
    public boolean isTerminal() {
        return status == GenerationStatus.COMPLETED ||
                status == GenerationStatus.FAILED ||
                status == GenerationStatus.CANCELLED;
    }

    /**
     * Get the duration of the job in seconds
     */
    public Long getDurationSeconds() {
        if (startedAt == null) {
            return 0L;
        }
        LocalDateTime endTime = completedAt != null ? completedAt : LocalDateTime.now();
        return Duration.between(startedAt, endTime).getSeconds();
    }

    /**
     * Get estimated time remaining in seconds
     */
    public Long getEstimatedTimeRemainingSeconds() {
        if (status.isTerminal() || progressPercentage == null || progressPercentage <= 0) {
            return 0L;
        }

        Long elapsedSeconds = getDurationSeconds();
        double progress = progressPercentage / 100.0;

        if (progress <= 0) {
            return 0L;
        }

        long totalEstimatedSeconds = (long) (elapsedSeconds / progress);
        return totalEstimatedSeconds - elapsedSeconds;
    }

    /**
     * Check if the job has billing activity
     */
    public boolean hasBillingActivity() {
        return billingState != null && !billingState.isNone();
    }

    /**
     * Check if tokens are currently reserved for this job
     */
    public boolean hasReservedTokens() {
        return billingState != null && billingState.isReserved();
    }

    /**
     * Check if tokens have been committed for this job
     */
    public boolean hasCommittedTokens() {
        return billingState != null && billingState.isCommitted();
    }

    /**
     * Check if the reservation has expired
     */
    public boolean isReservationExpired() {
        return reservationExpiresAt != null && LocalDateTime.now().isAfter(reservationExpiresAt);
    }

    /**
     * Get the remaining time until reservation expires (in seconds)
     */
    public Long getReservationTimeRemainingSeconds() {
        if (reservationExpiresAt == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(reservationExpiresAt)) {
            return 0L;
        }
        return Duration.between(now, reservationExpiresAt).getSeconds();
    }

    /**
     * Add a billing idempotency key to the job's audit trail
     */
    public void addBillingIdempotencyKey(String operation, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> keys = new HashMap<>();
            
            // Parse existing keys if present
            if (billingIdempotencyKeys != null && !billingIdempotencyKeys.isBlank()) {
                keys = mapper.readValue(billingIdempotencyKeys, Map.class);
            }
            
            // Add new key
            keys.put(operation, key);
            
            // Serialize back to JSON
            billingIdempotencyKeys = mapper.writeValueAsString(keys);
        } catch (Exception e) {
            // If JSON parsing fails, create a simple map with the new key
            billingIdempotencyKeys = "{\"" + operation + "\":\"" + key + "\"}";
        }
    }

    /**
     * Get a billing idempotency key by operation
     */
    public String getBillingIdempotencyKey(String operation) {
        if (billingIdempotencyKeys == null || billingIdempotencyKeys.isBlank()) {
            return null;
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> keys = mapper.readValue(billingIdempotencyKeys, Map.class);
            return keys.get(operation);
        } catch (Exception e) {
            return null;
        }
    }
} 