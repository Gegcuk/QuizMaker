package uk.gegc.quizmaker.features.attempt.application.impl;

import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.AttemptReadFactsService;
import uk.gegc.quizmaker.features.attempt.application.ParticipantViewDisclosureReader;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;

/** #556 supplies actual completed-review release proof. Submission alone cannot grant that proof. */
@Service
public class InitialParticipantViewDisclosureReader implements ParticipantViewDisclosureReader {
    @Override public ContentVisibility contentVisibility(AttemptReadFactsService.Facts facts) {
        return facts.access().lifecycle().state().execution() == AttemptLifecycle.Execution.IN_PROGRESS
                ? ContentVisibility.VISIBLE : ContentVisibility.WITHHELD;
    }
}
