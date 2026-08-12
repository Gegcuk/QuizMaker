package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "quiz_generation_output_checkpoints")
@Getter
@NoArgsConstructor
public class QuizGenerationOutputCheckpoint {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", insertable = false, updatable = false)
    private QuizGenerationJob job;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private short schemaVersion;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "MEDIUMTEXT")
    private String payload;

    @Column(name = "question_count", nullable = false, updatable = false)
    private int questionCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public QuizGenerationOutputCheckpoint(
            UUID jobId,
            int schemaVersion,
            String payload,
            int questionCount,
            LocalDateTime createdAt
    ) {
        this.jobId = Objects.requireNonNull(jobId, "jobId must not be null");
        if (schemaVersion <= 0 || schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("schemaVersion must be a positive SMALLINT value");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        if (questionCount <= 0) {
            throw new IllegalArgumentException("questionCount must be positive");
        }
        this.schemaVersion = (short) schemaVersion;
        this.payload = payload;
        this.questionCount = questionCount;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public boolean matches(int expectedSchemaVersion, String expectedPayload, int expectedQuestionCount) {
        return schemaVersion == expectedSchemaVersion
                && questionCount == expectedQuestionCount
                && payload.equals(expectedPayload);
    }
}
