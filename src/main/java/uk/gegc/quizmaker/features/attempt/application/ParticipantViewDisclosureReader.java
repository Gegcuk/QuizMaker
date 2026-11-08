package uk.gegc.quizmaker.features.attempt.application;

/** Project-owned release-read boundary. Implementations need current, revision/policy-bound evidence. */
public interface ParticipantViewDisclosureReader {
    enum ContentVisibility { VISIBLE, WITHHELD }
    ContentVisibility contentVisibility(AttemptReadFactsService.Facts facts);
    default Projection project(AttemptReadFactsService.Facts facts) {
        return new Projection(contentVisibility(facts), null);
    }
    record Projection(ContentVisibility visibility,
                      uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView result) { }
}
