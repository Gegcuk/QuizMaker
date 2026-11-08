package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.*;
import uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Cursor;

/** Transactional metadata only. The application boundary owns authorization and serialization. */
public interface NavigationRepository {
    void initialize(UUID attemptId, Cursor cursor);
    Optional<Cursor> read(UUID attemptId);
    boolean move(UUID attemptId, long expectedVersion, UUID target);
    void record(Result result);
    Optional<Result> result(UUID commandId);

    record Result(UUID commandId, UUID attemptId, UUID targetFormItemId, Cursor cursor) { }
}
