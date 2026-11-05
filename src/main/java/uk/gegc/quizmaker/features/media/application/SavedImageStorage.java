package uk.gegc.quizmaker.features.media.application;

import uk.gegc.quizmaker.features.media.domain.model.SavedImageVersion;

/** Platform storage boundary. Implementations perform no retries, cleanup, or database work. */
public interface SavedImageStorage {
    record Source(String etag, long size, String mimeType) { }
    Source inspect(String key);
    /** Reconcile the durable destination before copying the captured source conditionally. */
    void retain(SavedImageVersion version);
    void verify(SavedImageVersion version);
}
