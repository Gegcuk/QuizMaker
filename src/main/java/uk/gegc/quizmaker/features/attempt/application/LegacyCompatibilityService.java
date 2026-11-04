package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest;

/** Internal read-only inventory. No endpoint, scheduler, authorization grant or engine activation. */
public interface LegacyCompatibilityService {
    Report inventory(Limits limits);
    Finding classifyLaunch(LegacyPracticeRequest request);
}
