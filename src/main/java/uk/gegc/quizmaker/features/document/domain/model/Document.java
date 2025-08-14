package uk.gegc.quizmaker.model.document;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false)
    private String filePath;

    @Column(nullable = false)
    private DocumentStatus status;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User uploadedBy;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentChunk> chunks = new ArrayList<>();

    @Column
    private String title;

    @Column
    private String author;

    @Column
    private Integer totalPages;

    @Column
    private Integer totalChunks;

    @Column(columnDefinition = "TEXT")
    private String processingError;

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
        if (processedAt == null) {
            processedAt = LocalDateTime.now(); // Set to current time initially, will be updated during processing
        }
    }

    public enum DocumentStatus {
        UPLOADED,
        PROCESSING,
        PROCESSED,
        FAILED
    }
} 