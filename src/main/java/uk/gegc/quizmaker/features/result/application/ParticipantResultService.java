package uk.gegc.quizmaker.features.result.application;

import java.util.Optional;
import uk.gegc.quizmaker.features.attempt.application.AttemptReadFactsService;
import uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView;

/** Requires the participant projection transaction; rechecks current RESULT access independently. */
public interface ParticipantResultService {
    Optional<ParticipantResultView> read(AttemptReadFactsService.Facts facts);
}
