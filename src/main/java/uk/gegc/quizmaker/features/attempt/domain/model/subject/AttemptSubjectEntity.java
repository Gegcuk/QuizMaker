package uk.gegc.quizmaker.features.attempt.domain.model.subject;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.UUID;

/**
 * Read-only schema mapping: production validation and Hibernate-managed test schemas must know
 * the retained user FK. Runtime writes/reads belong to AttemptSubjectService's JDBC store.
 * No setters, cascade, entity repository or serialization path creates a second writer.
 */
@Entity
@Immutable
@Table(name = "attempt_participant_subject", indexes = @Index(name = "ix_attempt_subject_user", columnList = "user_id,attempt_id"))
@Check(constraints = "schema_version = 1 AND CAST(subject_type AS BINARY) = CAST('AUTHENTICATED_USER' AS BINARY)")
public class AttemptSubjectEntity {
    @Id
    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private short schemaVersion;

    @Column(name = "subject_type", length = 32, nullable = false, updatable = false)
    private String subjectType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_attempt_subject_user"))
    private User user;

    protected AttemptSubjectEntity() { }
}
