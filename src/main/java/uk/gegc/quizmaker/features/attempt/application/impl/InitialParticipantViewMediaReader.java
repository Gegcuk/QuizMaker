package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.ParticipantViewMediaReader;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;

/** Text-only baseline. Missing media integration is not evidence of a temporary storage outage. */
@Service
public class InitialParticipantViewMediaReader implements ParticipantViewMediaReader {
    @Override public Map<UUID, Image> read(UUID attemptId, UUID quizRevisionId, List<Reference> references) {
        if (!references.isEmpty()) throw new AttemptDeliveryException(AttemptDeliveryException.Reason.UNSUPPORTED);
        return Map.of();
    }
}
