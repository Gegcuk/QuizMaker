package uk.gegc.quizmaker.features.attempt.application;

/** Project-owned release-read boundary. Implementations need current, revision/policy-bound evidence. */
public interface ParticipantViewDisclosureReader {
    enum ContentVisibility { VISIBLE, WITHHELD }
    ContentVisibility contentVisibility(AttemptReadFactsService.Facts facts);
}
