package uk.gegc.quizmaker.features.learning.application;

import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import java.util.List;
import java.util.UUID;

/** Authenticated, online single-quiz FSRS commands. Server time, source access and cycle identity are authoritative. */
public interface LearningStudyService {
    record Enroll(UUID commandId, Source source, List<UUID> questions) { }
    record Start(UUID commandId, Source source, int limit) { }
    record EnrolledItem(UUID questionRevisionId, UUID cycleId, long cycleVersion) { }
    record Enrollment(Source source, List<EnrolledItem> items) {
        public Enrollment { items = List.copyOf(items); }
    }

    Enrollment enroll(Enroll command);
    LearningFormService.View start(Start command);
    LearningFormService.View resume(UUID sessionId);
}
