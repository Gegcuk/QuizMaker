package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;

import java.util.List;

/** Internal pure boundary. Callers must revalidate current authority and capabilities at actual start. */
public interface AttemptPresetService {
    List<PresetReference> supportedPresets();
    BuiltInPracticePreset load(PresetReference reference);
    LegacyPracticeMapping mapLegacy(LegacyPracticeRequest request);
}
