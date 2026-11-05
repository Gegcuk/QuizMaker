package uk.gegc.quizmaker.features.media.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageVersion;

import java.util.*;

public interface SavedImageVersionRepository extends JpaRepository<SavedImageVersion, UUID> {
    Optional<SavedImageVersion> findByAssetId(UUID assetId);
    List<SavedImageVersion> findAllByAssetIdInAndReadyTrue(Collection<UUID> ids);
}
