package uk.gegc.quizmaker.repository.repetition;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.model.repetition.SpacedRepetitionEntry;

import java.util.UUID;

public interface SpacedRepetitionEntryRepository extends JpaRepository<SpacedRepetitionEntry, UUID> {
}
