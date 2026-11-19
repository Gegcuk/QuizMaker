package uk.gegc.quizmaker.features.result.domain.repository;

import java.util.*;
import uk.gegc.quizmaker.features.result.domain.model.notification.ResultNotificationEvent;

/** Transaction-bound, insert-only #837 outbox. Only real immediate first release is supported. */
public interface ResultNotificationEventRepository {
    void insert(ResultNotificationEvent event);
    Optional<ResultNotificationEvent> find(UUID id);
    List<ResultNotificationEvent> scan(UUID after, int limit);
}
