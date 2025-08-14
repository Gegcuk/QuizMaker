package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;
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
    }

    @PreUpdate
    protected void onUpdate() {
        if (status == GenerationStatus.COMPLETED || status == GenerationStatus.FAILED) {
            if (completedAt == null) {
                completedAt = LocalDateTime.now();
            }
            if (generationTimeSeconds == null && startedAt != null) {
                generationTimeSeconds = java.time.Duration.between(startedAt, completedAt).getSeconds();
            }
        }

        // Update progress percentage
        if (totalChunks != null && totalChunks > 0) {
            progressPercentage = (double) processedChunks / totalChunks * 100.0;
        }
    }

    /**
     * Update progress for the generation job
     */
    public void updateProgress(int processedChunks, String currentChunk) {
        this.processedChunks = processedChunks;
        this.currentChunk = currentChunk;

        if (totalChunks != null && totalChunks > 0) {
            this.progressPercentage = (double) processedChunks / totalChunks * 100.0;
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
        // Ensure processedChunks equals totalChunks to maintain consistency with @PreUpdate
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
        return java.time.Duration.between(startedAt, endTime).getSeconds();
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
} 