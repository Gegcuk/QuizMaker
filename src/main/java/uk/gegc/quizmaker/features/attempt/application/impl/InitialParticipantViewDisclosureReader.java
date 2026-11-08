package uk.gegc.quizmaker.features.attempt.application.impl;

import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.AttemptReadFactsService;
import uk.gegc.quizmaker.features.attempt.application.ParticipantViewDisclosureReader;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;

/** Active content and committed terminal review have distinct proof. */
@Service
public class InitialParticipantViewDisclosureReader implements ParticipantViewDisclosureReader {
    private final uk.gegc.quizmaker.features.result.application.ParticipantResultService results;
    public InitialParticipantViewDisclosureReader(uk.gegc.quizmaker.features.result.application.ParticipantResultService results) {
        this.results = results;
    }
    @Override public ContentVisibility contentVisibility(AttemptReadFactsService.Facts facts) {
        return project(facts).visibility();
    }
    @Override public Projection project(AttemptReadFactsService.Facts facts) {
        if (facts.access().lifecycle().state().execution() == AttemptLifecycle.Execution.IN_PROGRESS)
            return new Projection(ContentVisibility.VISIBLE, null);
        var result = results.read(facts).orElse(null);
        return new Projection(result != null && Boolean.TRUE.equals(result.reviewAvailable())
                ? ContentVisibility.VISIBLE : ContentVisibility.WITHHELD, result);
    }
}
