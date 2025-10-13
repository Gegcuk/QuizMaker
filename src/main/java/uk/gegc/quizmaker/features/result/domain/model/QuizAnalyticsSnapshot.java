package uk.gegc.quizmaker.features.result.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent analytics snapshot for a quiz.
 * <p>
 * Maintains pre-computed aggregate statistics (attempts count, average/best/worst scores, pass rate)
 * to avoid expensive on-demand aggregation for large datasets. Updated event-driven when attempts
 * are completed, with fallback recomputation via scheduled jobs or on-demand refresh.
 * </p>
 * <p>
 * One snapshot per quiz (quiz_id is the primary key). Uses optimistic locking to prevent lost
 * updates during concurrent attempt completions.
 * </p>
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "quiz_analytics_snapshot")
public class QuizAnalyticsSnapshot {

    @Id
    @Column(name = "quiz_id", nullable = false, updatable = false)
    private UUID quizId;

    @Column(name = "attempts_count", nullable = false)
    private long attemptsCount;

    @Column(name = "average_score", nullable = false)
    private double averageScore;

    @Column(name = "best_score", nullable = false)
    private double bestScore;

    @Column(name = "worst_score", nullable = false)
    private double worstScore;

    @Column(name = "pass_rate", nullable = false)
    private double passRate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}

