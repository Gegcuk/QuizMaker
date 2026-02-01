package uk.gegc.quizmaker.features.repetition.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepetitionReminderServiceImpl implements RepetitionReminderService{
    private final SpacedRepetitionEntryRepository spacedRepetitionEntryRepository;

    @Override
    @Transactional
    public SpacedRepetitionEntry setReminderEnabled(UUID entryId, UUID userId, boolean enabled) {
        SpacedRepetitionEntry entry = spacedRepetitionEntryRepository.findByIdAndUser_Id(entryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Entry " + entryId + " not found for user " + userId));
        entry.setReminderEnabled(enabled);
        return spacedRepetitionEntryRepository.save(entry);
    }
}
