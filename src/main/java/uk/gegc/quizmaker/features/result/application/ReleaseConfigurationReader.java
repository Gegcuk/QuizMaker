package uk.gegc.quizmaker.features.result.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationService.Publication;

/**
 * Captured-reference reader for trusted consumers in an existing RC transaction. The caller must
 * establish current access and release authority independently; identifiers are not credentials.
 * Never resolve the mutable authoring head for an existing attempt/assignment binding.
 */
public interface ReleaseConfigurationReader {
    Publication readBound(UUID contentOwnerId, UUID quizId, UUID revisionId, UUID configurationId);
}
