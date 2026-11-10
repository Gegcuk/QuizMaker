package uk.gegc.quizmaker.features.attempt.application;

/** Reads captured evidence for a trusted system command holding the lifecycle root lock. */
public interface AttemptExpiryFactsService {
    AttemptReadFactsService.Facts read(AttemptAccessService.Access captured);
}
