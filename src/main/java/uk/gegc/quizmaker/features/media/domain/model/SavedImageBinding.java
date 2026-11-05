package uk.gegc.quizmaker.features.media.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Retained evidence of an authorized draft binding; removing a draft reference never erases it. */
@Entity
@Table(name = "saved_image_bindings", uniqueConstraints = @UniqueConstraint(columnNames = {"question_id", "asset_id"}))
@Getter
@NoArgsConstructor
public class SavedImageBinding {
    @Id
    private UUID id;
    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;
    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;
    @Column(name = "image_version_id", nullable = false, updatable = false)
    private UUID imageVersionId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SavedImageBinding(UUID questionId, SavedImageVersion version, Instant now) {
        id = UUID.randomUUID();
        this.questionId = questionId;
        assetId = version.getAssetId();
        imageVersionId = version.getId();
        createdAt = now;
    }
}
