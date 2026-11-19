package uk.gegc.quizmaker.features.result.infra.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.Category;
import uk.gegc.quizmaker.features.result.domain.model.notification.*;
import uk.gegc.quizmaker.features.result.domain.repository.ResultNotificationEventRepository;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultNotificationEvent.*;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultEventException.*;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultEventException.Reason.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcResultNotificationEventRepository implements ResultNotificationEventRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String SELECT = """
            SELECT n.schema_version,n.event_kind,n.source_kind,n.source_version,n.occurred_at,n.category_mask,
                BIN_TO_UUID(n.id) event_id,BIN_TO_UUID(n.source_id) source_id,BIN_TO_UUID(n.target_id) target_id,
                r.id=n.source_id AND r.id=n.id AND r.result_id=n.target_id AND r.schema_version=n.source_version
                    AND r.released_at=n.occurred_at AS consistent
            FROM attempt_result_notification_event n LEFT JOIN attempt_result_release r ON r.id=n.source_id
            """;
    public JdbcResultNotificationEventRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void insert(ResultNotificationEvent event) {
        try {
            require(event != null, INVALID_INPUT);
            require(event.kind() == Kind.RESULT_OPENED && event.source().kind() == SourceKind.RESULT_RELEASE
                    && event.source().version() == 1 && event.categories().contains(Category.TOTAL_SCORE), UNSUPPORTED);
            int changed = jdbc.update("""
                    INSERT INTO attempt_result_notification_event
                        (id,schema_version,event_kind,source_kind,source_id,source_version,target_id,occurred_at,category_mask)
                    SELECT r.id,1,'RESULT_OPENED','RESULT_RELEASE',r.id,r.schema_version,r.result_id,r.released_at,:mask
                    FROM attempt_result_release r WHERE r.id=UUID_TO_BIN(:id) AND r.result_id=UUID_TO_BIN(:target)
                        AND r.schema_version=:version AND r.released_at=:at
                        AND NOT EXISTS (SELECT 1 FROM attempt_scheduled_release s WHERE s.result_id=r.result_id)
                    """, new MapSqlParameterSource("id", event.id().toString()).addValue("target", event.targetId().toString())
                    .addValue("version", event.source().version()).addValue("at", LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                    .addValue("mask", mask(event.categories())));
            require(changed == 1, CORRUPT);
        } catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
    @Override public Optional<ResultNotificationEvent> find(UUID id) {
        try {
            require(id != null, INVALID_INPUT);
            return jdbc.query(SELECT + " WHERE n.id=UUID_TO_BIN(:id)", Map.of("id", id.toString()), this::row).stream().findFirst();
        } catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
    @Override public List<ResultNotificationEvent> scan(UUID after, int limit) {
        try {
            require(limit >= 1 && limit <= 501, INVALID_INPUT);
            var parameters = new MapSqlParameterSource("limit", limit);
            String condition = after == null ? "" : " WHERE n.id>UUID_TO_BIN(:after)";
            if (after != null) parameters.addValue("after", after.toString());
            return jdbc.query(SELECT + condition + " ORDER BY n.id LIMIT :limit", parameters, this::row);
        } catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
    private ResultNotificationEvent row(ResultSet row, int index) throws SQLException {
        try {
            require(row.getBoolean("consistent"), CORRUPT);
            int bits = row.getInt("category_mask"); require(bits == 1 || bits == 3, CORRUPT);
            var event = new ResultNotificationEvent(row.getInt("schema_version"), Kind.valueOf(row.getString("event_kind")),
                    new Source(SourceKind.valueOf(row.getString("source_kind")), UUID.fromString(row.getString("source_id")), row.getLong("source_version")),
                    UUID.fromString(row.getString("target_id")), null, row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    bits == 1 ? Set.of(Category.TOTAL_SCORE) : Set.of(Category.TOTAL_SCORE, Category.PASS_FAIL));
            require(event.kind() == Kind.RESULT_OPENED && event.id().toString().equals(row.getString("event_id")), CORRUPT);
            return event;
        } catch (IllegalArgumentException | NullPointerException failure) { throw new ResultEventException(CORRUPT); }
    }
    private static int mask(Set<Category> categories) {
        return (categories.contains(Category.TOTAL_SCORE) ? 1 : 0) | (categories.contains(Category.PASS_FAIL) ? 2 : 0);
    }
}
