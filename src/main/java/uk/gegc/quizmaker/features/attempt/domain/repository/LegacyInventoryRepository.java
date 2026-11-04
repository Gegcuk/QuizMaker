package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.Family;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.Observation;

import java.util.List;
import java.util.UUID;

public interface LegacyInventoryRepository {
    /** Ascending unsigned binary UUID order, exclusive cursor; at most limit metadata-only rows. */
    List<Observation> read(Family family, UUID after, int limit);
}
