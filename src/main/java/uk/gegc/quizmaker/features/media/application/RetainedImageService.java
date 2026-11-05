package uk.gegc.quizmaker.features.media.application;

import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.util.*;

public interface RetainedImageService {
    /** Must run outside a transaction, after the owning feature authorizes the proposed write. */
    void prepare(Collection<UUID> assetIds, String username);
    void prepareForQuestion(UUID questionId, Set<UUID> desired, Set<UUID> previous, String username);
    /** Same transaction as the question write. Previous IDs come from the locked persisted question. */
    void bind(UUID questionId, Set<UUID> desired, Set<UUID> previous, String username);
    /** Existing bindings only, after the consumer rechecks current question/publication authority. */
    Map<UUID, ImageReference> acquireForPublication(UUID questionId, Set<UUID> assetIds, Runnable authorize);
    /** Internal read projection; calling feature must already have authorized its resource. */
    Map<UUID, MediaRefDto> resolve(Collection<UUID> assetIds);
    Map<UUID, ImageVersion> describe(Collection<UUID> assetIds);
    void remove(UUID assetId, String username);
    void restore(UUID assetId, String username);

    record ImageReference(UUID bindingId, UUID versionId, UUID assetId, String objectKey,
                          String mimeType, long sizeBytes, int width, int height) { }
    record ImageVersion(String objectKey, String cdnUrl, String mimeType, long sizeBytes, int width, int height) { }
}
