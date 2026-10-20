package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable dispatch identity. Absence deliberately preserves the legacy lifecycle. */
@Entity
@Table(name = "quiz_generation_work")
@Getter
@NoArgsConstructor
public class QuizGenerationWork {
    public enum State { READY, RUNNING, DONE }

    @Id
    @Column(name = "job_id")
    private UUID jobId;
    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion = 1;
    @Column(name = "source_revision", nullable = false, updatable = false)
    private long sourceRevision;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;
    @Column(nullable = false)
    private int executions;
    @Column(nullable = false)
    private long fence;
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "admission_sequence", nullable = false, updatable = false)
    private long admissionSequence;
    @Column(name = "deadline_enabled", nullable = false)
    private boolean deadlineEnabled = true;
    @Column(name = "first_started_at")
    private Instant firstStartedAt;
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    public QuizGenerationWork(UUID jobId, long sourceRevision, LocalDateTime now, long sequence) {
        this(jobId, sourceRevision, now);
        this.admissionSequence = sequence;
    }

    public void startDeadline(Instant now) {
        if (deadlineEnabled && firstStartedAt == null) {
            firstStartedAt = Objects.requireNonNull(now);
            deadlineAt = now.plusSeconds(300);
        }
    }

    public boolean deadlineExpired(Instant now) {
        return deadlineAt != null && !now.isBefore(deadlineAt);
    }

    public QuizGenerationWork(UUID jobId, long sourceRevision, LocalDateTime now) {
        this.jobId = Objects.requireNonNull(jobId);
        this.sourceRevision = sourceRevision;
        this.createdAt = Objects.requireNonNull(now);
        this.state = State.READY;
    }

    public boolean isDue(LocalDateTime now) {
        return state == State.READY || (state == State.RUNNING && !leaseUntil.isAfter(now));
    }

    public long claim(LocalDateTime now, int leaseSeconds) {
        if (!isDue(now) || executions >= 2) {
            throw new IllegalStateException("Generation work cannot be claimed");
        }
        executions++;
        fence++;
        state = State.RUNNING;
        leaseUntil = now.plusSeconds(leaseSeconds);
        return fence;
    }

    public boolean owns(long expectedFence, LocalDateTime now) {
        return state == State.RUNNING && fence == expectedFence && leaseUntil.isAfter(now);
    }

    public void heartbeat(LocalDateTime now, int leaseSeconds) {
        leaseUntil = now.plusSeconds(leaseSeconds);
    }

    public void finish() {
        state = State.DONE;
        leaseUntil = null;
    }
}
