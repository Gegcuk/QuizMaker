package uk.gegc.quizmaker.features.attempt.application;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Batched reader of exact published image bindings and current display authority. No mutable/external
 * fallback. #818/#831 own runtime integration. No storage network I/O may run under the caller's locks.
 */
public interface ParticipantViewMediaReader {
    Map<UUID, Image> read(UUID attemptId, UUID quizRevisionId, List<Reference> references);

    record Reference(UUID formItemId, UUID questionRevisionId, UUID assetId) { }
    enum Availability { AVAILABLE, UNAVAILABLE, RESTRICTED }
    record Image(Availability availability, String url) {
        public Image {
            if (availability == null || availability != Availability.AVAILABLE && url != null)
                throw new IllegalArgumentException("Invalid image projection");
            if (availability == Availability.AVAILABLE) {
                try {
                    var uri = URI.create(url);
                    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                            || uri.getHost() == null || uri.getRawUserInfo() != null)
                        throw new IllegalArgumentException();
                } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid image projection"); }
            }
        }
        @Override public String toString() { return "ParticipantImage[availability=" + availability + "]"; }
    }
}
