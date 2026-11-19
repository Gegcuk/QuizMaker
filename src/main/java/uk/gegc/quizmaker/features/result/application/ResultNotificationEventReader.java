package uk.gegc.quizmaker.features.result.application;

import java.util.*;
import uk.gegc.quizmaker.features.result.domain.model.notification.ResultNotificationEvent;

/**
 * Trusted internal consumer boundary; IDs grant no participant access. #800 must resolve current
 * recipient/access and owns delivery/dedup. A cursor continues one scan, NOT a commit high-water
 * mark: concurrent transactions may commit lower IDs later. Start subsequent scans at null.
 * Reading or reaching the end never acknowledges or deletes an event.
 */
public interface ResultNotificationEventReader {
    Optional<ResultNotificationEvent> find(UUID id);
    Page scan(UUID after, int limit);
    record Page(List<ResultNotificationEvent> events, UUID nextCursor) {
        public Page { events = List.copyOf(events); }
        @Override public String toString() { return "ResultEventPage[size=" + events.size() + "]"; }
    }
}
