package uk.gegc.quizmaker.features.repetition.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;

import java.util.UUID;

public interface SpacedRepetitionEntryRepository extends JpaRepository<SpacedRepetitionEntry, UUID> {
}
