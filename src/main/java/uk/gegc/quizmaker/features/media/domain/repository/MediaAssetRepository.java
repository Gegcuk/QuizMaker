package uk.gegc.quizmaker.features.media.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.media.domain.model.MediaAsset;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    @Query("""
        SELECT m FROM MediaAsset m
        WHERE (:type IS NULL OR m.type = :type)
          AND (:status IS NULL OR m.status = :status)
          AND m.status <> uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus.DELETED
          AND (:query IS NULL OR LOWER(m.originalFilename) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(m.key) LIKE LOWER(CONCAT('%', :query, '%')))
        ORDER BY m.createdAt DESC
    """)
    Page<MediaAsset> search(
            @Param("type") MediaAssetType type,
            @Param("status") MediaAssetStatus status,
            @Param("query") String query,
            Pageable pageable
    );

    Optional<MediaAsset> findByIdAndStatusNot(UUID id, MediaAssetStatus status);
}
