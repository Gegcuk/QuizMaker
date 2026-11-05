package uk.gegc.quizmaker.features.media.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Immutable source/object identity; only preparation completion may change. No byte hashing on reads. */
@Entity
@Table(name = "saved_image_versions", uniqueConstraints = @UniqueConstraint(columnNames = "asset_id"))
@Getter
@NoArgsConstructor
public class SavedImageVersion {
    @Id
    private UUID id;
    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;
    @Column(name = "source_key", nullable = false, updatable = false, length = 1024)
    private String sourceKey;
    @Column(name = "source_etag", nullable = false, updatable = false, length = 255)
    private String sourceEtag;
    @Column(name = "object_key", nullable = false, updatable = false, length = 1024)
    private String objectKey;
    @Column(name = "mime_type", nullable = false, updatable = false)
    private String mimeType;
    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;
    @Column(nullable = false, updatable = false)
    private int width;
    @Column(nullable = false, updatable = false)
    private int height;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private boolean ready;

    public SavedImageVersion(UUID assetId, String sourceKey, String etag, String mimeType,
                             long size, int width, int height, Instant now) {
        this.id = UUID.randomUUID();
        this.assetId = assetId;
        this.sourceKey = sourceKey;
        this.sourceEtag = etag;
        this.objectKey = "retained-images/" + assetId + "/" + id;
        this.mimeType = mimeType;
        this.sizeBytes = size;
        this.width = width;
        this.height = height;
        this.createdAt = now;
    }

    public void markReady() { ready = true; }
}
