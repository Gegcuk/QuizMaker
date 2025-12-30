package uk.gegc.quizmaker.features.media.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
@Getter
@Setter
@NoArgsConstructor
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "asset_id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private MediaAssetType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MediaAssetStatus status = MediaAssetStatus.UPLOADING;

    @Column(name = "object_key", nullable = false, unique = true, length = 1024)
    private String key;

    @Column(name = "mime_type", nullable = false, length = 255)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "original_filename", length = 512)
    private String originalFilename;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "article_id")
    private UUID articleId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public boolean isImage() {
        return MediaAssetType.IMAGE.equals(type);
    }
}
