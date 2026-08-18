package uk.gegc.quizmaker.features.documentProcess.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "normalized_documents",
        indexes = @Index(name = "ix_normalized_documents_owner_id", columnList = "owner_id")
)
@Getter
@Setter
public class NormalizedDocument {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "owner_id",
            foreignKey = @ForeignKey(name = "fk_normalized_documents_owner")
    )
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User owner;

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
        PENDING, NORMALIZED, FAILED, STRUCTURED
    }
}
