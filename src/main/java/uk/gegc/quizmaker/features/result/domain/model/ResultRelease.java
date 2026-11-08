package uk.gegc.quizmaker.features.result.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

/** Insert-only initial immediate release. The unique result binding is its durable receipt. */
public record ResultRelease(UUID id, int version, UUID resultId, Instant releasedAt) {
    public ResultRelease {
        require(version == 1, UNSUPPORTED);
        require(id != null && resultId != null && releasedAt != null, CORRUPT);
        releasedAt = releasedAt.truncatedTo(ChronoUnit.MICROS);
    }
    @Override public String toString() { return "ResultRelease[version=" + version + "]"; }
}
