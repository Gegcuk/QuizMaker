package uk.gegc.quizmaker.features.documentProcess.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
@Getter
@Setter
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "original_name")
    private String originalName;

    @Column(name = "mime")
    private String mime;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private DocumentSource source;

    @Column(name = "language")
    private String language;

    @Column(name = "normalized_text", columnDefinition = "LONGTEXT")
    private String normalizedText;

    @Column(name = "char_count")
    private Integer charCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DocumentStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum DocumentSource {
        UPLOAD, TEXT
    }

    public enum DocumentStatus {
        PENDING, NORMALIZED, FAILED
    }
}
