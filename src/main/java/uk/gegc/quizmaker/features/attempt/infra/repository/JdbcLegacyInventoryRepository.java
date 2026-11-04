package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.Family;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.Observation;
import uk.gegc.quizmaker.features.attempt.domain.repository.LegacyInventoryRepository;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Keyset windows of scalar metadata. Never selects answer JSON, tokens, names or source content. */
@Repository
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
public class JdbcLegacyInventoryRepository implements LegacyInventoryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLegacyInventoryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Observation> read(Family family, UUID after, int limit) {
        Objects.requireNonNull(family);
        if (limit < 1 || limit > 1001) throw new IllegalArgumentException("Invalid legacy inventory page size");
        var source = source(family);
        String page = "(SELECT " + source.columns() + " FROM " + source.table()
                + (after == null ? "" : " WHERE " + source.key() + ">:after")
                + " ORDER BY " + source.key() + " LIMIT :size) r";
        String sql = "SELECT r.id, " + source.broken() + " AS broken_reference, "
                + source.unknown() + " AS unknown_value, " + source.ambiguous() + " AS ambiguous_identity, "
                + source.inconsistent() + " AS inconsistent_state, " + source.duplicate() + " AS duplicate_answer, "
                + source.repetition() + " AS unexpected_repetition FROM " + page + source.joins() + " ORDER BY r.id";
        var parameters = new MapSqlParameterSource("size", limit);
        if (after != null) parameters.addValue("after", ByteBuffer.allocate(16)
                .putLong(after.getMostSignificantBits()).putLong(after.getLeastSignificantBits()).array());
        return jdbc.query(sql, parameters, (row, index) -> {
            var id = ByteBuffer.wrap(row.getBytes("id"));
            return new Observation(new UUID(id.getLong(), id.getLong()), row.getBoolean("broken_reference"),
                    row.getBoolean("unknown_value"), row.getBoolean("ambiguous_identity"),
                    row.getBoolean("inconsistent_state"), row.getBoolean("duplicate_answer"),
                    row.getBoolean("unexpected_repetition"));
        });
    }

    private record Source(String table, String key, String columns, String joins, String broken,
                          String unknown, String ambiguous, String inconsistent, String duplicate, String repetition) { }

    private static Source source(Family family) {
        return switch (family) {
            case ATTEMPT -> new Source("attempts", "id",
                    "id,user_id,quiz_id,share_link_id,mode,status,started_at,completed_at,total_score",
                    " LEFT JOIN users u ON u.user_id=r.user_id LEFT JOIN quizzes q ON q.quiz_id=r.quiz_id"
                            + " LEFT JOIN share_links s ON s.id=r.share_link_id",
                    "(u.user_id IS NULL OR q.quiz_id IS NULL OR (r.share_link_id IS NOT NULL"
                            + " AND (s.id IS NULL OR s.quiz_id<>r.quiz_id)))",
                    "(r.mode IS NULL OR BINARY r.mode NOT IN ('ALL_AT_ONCE','ONE_BY_ONE','TIMED')"
                            + " OR r.status IS NULL OR BINARY r.status NOT IN ('IN_PROGRESS','PAUSED','COMPLETED','ABANDONED'))",
                    "((BINARY u.username='anonymous') <> (r.share_link_id IS NOT NULL))",
                    "(r.started_at IS NULL OR r.completed_at<r.started_at"
                            + " OR (r.status IN ('IN_PROGRESS','PAUSED') AND (r.completed_at IS NOT NULL OR r.total_score IS NOT NULL))"
                            + " OR (r.status='COMPLETED' AND (r.completed_at IS NULL OR r.total_score IS NULL)))",
                    "FALSE", "FALSE");
            case ANSWER -> new Source("answers", "id", "id,attempt_id,question_id,answered_at",
                    " LEFT JOIN attempts a ON a.id=r.attempt_id LEFT JOIN questions q ON q.id=r.question_id",
                    "(a.id IS NULL OR q.id IS NULL OR NOT EXISTS (SELECT 1 FROM quiz_questions membership"
                            + " WHERE membership.quiz_id=a.quiz_id AND membership.question_id=r.question_id))",
                    "(q.type IS NULL OR BINARY q.type NOT IN ('MCQ_SINGLE','MCQ_MULTI','OPEN','FILL_GAP','COMPLIANCE',"
                            + "'TRUE_FALSE','ORDERING','HOTSPOT','MATCHING'))",
                    "FALSE", "(r.answered_at IS NULL)",
                    "EXISTS (SELECT 1 FROM answers other WHERE other.attempt_id=r.attempt_id"
                            + " AND other.question_id=r.question_id AND other.id<>r.id)", "FALSE");
            case SHARE_LINK -> new Source("share_links", "id", "id,quiz_id,created_by,scope",
                    " LEFT JOIN quizzes q ON q.quiz_id=r.quiz_id LEFT JOIN users u ON u.user_id=r.created_by",
                    "(q.quiz_id IS NULL OR u.user_id IS NULL)",
                    "(r.scope IS NULL OR BINARY r.scope NOT IN ('QUIZ_VIEW','QUIZ_ATTEMPT_START'))",
                    "FALSE", "FALSE", "FALSE", "FALSE");
            case RESULT_SNAPSHOT -> new Source("quiz_analytics_snapshot", "quiz_id", "quiz_id AS id,attempts_count",
                    " LEFT JOIN quizzes q ON q.quiz_id=r.id", "(q.quiz_id IS NULL)", "FALSE", "FALSE",
                    "(r.attempts_count IS NULL OR r.attempts_count<0)", "FALSE", "FALSE");
            case QUIZ_CONFIGURATION -> new Source("quizzes", "quiz_id",
                    "quiz_id AS id,creator_id,category_id,status,visibility,is_timer_enabled,timer_duration_min,is_repetition_enabled",
                    " LEFT JOIN users u ON u.user_id=r.creator_id LEFT JOIN categories c ON c.category_id=r.category_id",
                    "(u.user_id IS NULL OR c.category_id IS NULL)",
                    "(r.status IS NULL OR BINARY r.status NOT IN ('DRAFT','PENDING_REVIEW','PUBLISHED','REJECTED','ARCHIVED')"
                            + " OR r.visibility IS NULL OR BINARY r.visibility NOT IN ('PUBLIC','PRIVATE')"
                            + " OR r.is_timer_enabled IS NULL OR r.is_repetition_enabled IS NULL"
                            + " OR r.is_timer_enabled NOT IN (0,1) OR r.is_repetition_enabled NOT IN (0,1))",
                    "FALSE", "(r.is_timer_enabled=1 AND (r.timer_duration_min IS NULL OR r.timer_duration_min<=0))",
                    "FALSE", "(r.is_repetition_enabled=1)");
            case REPETITION_ENTRY -> new Source("spaced_repetition_entry", "id", "id,user_id,question_id",
                    " LEFT JOIN users u ON u.user_id=r.user_id LEFT JOIN questions q ON q.id=r.question_id",
                    "(u.user_id IS NULL OR q.id IS NULL)", "FALSE", "FALSE", "FALSE", "FALSE", "TRUE");
            case REPETITION_REVIEW -> new Source("repetition_review_log", "repetition_review_id",
                    "repetition_review_id AS id,user_id,entry_id",
                    " LEFT JOIN users u ON u.user_id=r.user_id LEFT JOIN spaced_repetition_entry e ON e.id=r.entry_id",
                    "(u.user_id IS NULL OR e.id IS NULL OR e.user_id<>r.user_id)",
                    "FALSE", "FALSE", "FALSE", "FALSE", "TRUE");
            case SHARE_LINK_USAGE -> new Source("share_link_usage", "id", "id,share_link_id",
                    " LEFT JOIN share_links s ON s.id=r.share_link_id", "(s.id IS NULL)",
                    "FALSE", "FALSE", "FALSE", "FALSE", "FALSE");
            case SHARE_LINK_EVENT -> new Source("share_link_analytics", "id", "id,share_link_id,event_type",
                    " LEFT JOIN share_links s ON s.id=r.share_link_id", "(s.id IS NULL)",
                    "(r.event_type IS NULL OR BINARY r.event_type NOT IN ('VIEW','ATTEMPT_START','CONSUMED','CREATED','REVOKED'))",
                    "FALSE", "FALSE", "FALSE", "FALSE");
        };
    }
}
