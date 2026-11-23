package uk.gegc.quizmaker.features.learning.application;

import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

/** Authorized mixed-strategy session integration and explicit custom calendar rescheduling. */
public interface LearningCycleService {
    record Snapshot(Expected expected, Instant dueAt, boolean completed, boolean paused) { }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningTimezonePreview",description="Read-only calculation for one captured cycle/version. Confirmation revalidates every field; a later review requires a fresh preview.")
    record Preview(Source source, UUID questionRevisionId, Expected expected, long scheduleVersion,
                   String timeZone, Instant previousDueAt, Instant nextDueAt) {
        public Preview {
            uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require(source!=null && questionRevisionId!=null
                    && expected!=null && expected.cycleId()!=null && expected.cycleVersion()>0 && expected.sequence()>=0
                    && scheduleVersion>=0 && timeZone!=null && !timeZone.isBlank() && timeZone.length()<=255
                    && previousDueAt!=null && nextDueAt!=null,
                    uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.INVALID_INPUT);
        }
        @Override public String toString() { return "LearningTimezonePreview[redacted]"; }
    }
    Snapshot read(Source source,UUID question);
    Snapshot readyForCapture(Source source,UUID question);
    List<uk.gegc.quizmaker.features.learning.domain.model.LearningDueSelection.Item> due(Source source,int take);
    void capture(Source source,UUID question,Expected expected,UUID session,boolean practice);
    Instant rate(Source source,UUID question,Expected expected,Rating rating,UUID command,boolean requireDue);
    void release(Source source,UUID question,Expected expected,UUID session);
    Preview preview(Source source,UUID question,String timeZone);
    Acknowledgement confirm(UUID command,Preview preview,boolean confirmed);
}
