package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQuery.Entry;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException;
import uk.gegc.quizmaker.features.attempt.domain.repository.TraceQueryRepository;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException.Reason.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
public class JdbcTraceQueryRepository implements TraceQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcTraceQueryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Head> head(UUID attemptId) {
        require(attemptId != null, INVALID_QUERY);
        try {
            return jdbc.query("""
                    SELECT s.schema_version, s.attempt_id, s.policy_snapshot_id, s.form_id, s.last_sequence,
                      COALESCE((SELECT e.sequence_number FROM attempt_trace_event e FORCE INDEX (PRIMARY)
                        WHERE e.attempt_id=s.attempt_id ORDER BY e.sequence_number DESC LIMIT 1),0) AS tail
                    FROM attempt_trace_stream s WHERE s.attempt_id=:attempt
                    """, parameters(attemptId), (row, index) -> {
                try {
                    return new Head(row.getInt("schema_version"), new Scope(uuid(row, "attempt_id"),
                            uuid(row, "policy_snapshot_id"), uuid(row, "form_id")), row.getLong("last_sequence"), row.getLong("tail"));
                } catch (RuntimeException failure) { throw new TraceQueryException(CORRUPT_EVIDENCE); }
            }).stream().findFirst();
        } catch (DataAccessException failure) { throw new TraceQueryException(PERSISTENCE_UNAVAILABLE); }
    }

    @Override public List<Entry> page(UUID attemptId, long after, long through, int limit) {
        require(attemptId != null && after >= 0 && through > after && limit > 0
                && limit <= ExecutionTrace.MAX_PAGE_SIZE, INVALID_QUERY);
        try {
            return List.copyOf(jdbc.query("""
                    SELECT e.sequence_number, e.schema_version, e.event_type, e.actor_type, e.recorded_at,
                      e.from_execution, e.from_answers, e.to_execution, e.to_answers, e.reason_code
                    FROM attempt_trace_event e FORCE INDEX (PRIMARY)
                    WHERE e.attempt_id=:attempt AND e.sequence_number>:after AND e.sequence_number<=:through
                    ORDER BY e.sequence_number LIMIT :limit
                    """, parameters(attemptId).addValue("after", after).addValue("through", through).addValue("limit", limit),
                    (row, index) -> entry(row)));
        } catch (DataAccessException failure) { throw new TraceQueryException(PERSISTENCE_UNAVAILABLE); }
    }

    private static Entry entry(ResultSet row) throws SQLException {
        int version = row.getInt("schema_version");
        require(version == ExecutionTrace.VERSION, UNSUPPORTED_VERSION);
        try {
            return new Entry(row.getLong("sequence_number"), row.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    ExecutionTrace.parse(version, Type.class, row.getString("event_type")),
                    ExecutionTrace.parse(version, Actor.class, row.getString("actor_type")),
                    ExecutionTrace.parse(version, ReasonCode.class, row.getString("reason_code")), state(row, "from"), state(row, "to"));
        } catch (RuntimeException failure) { throw new TraceQueryException(CORRUPT_EVIDENCE); }
    }

    private static State state(ResultSet row, String prefix) throws SQLException {
        String execution = row.getString(prefix + "_execution"), answers = row.getString(prefix + "_answers");
        if (execution == null && answers == null) return null;
        return new State(1, AttemptLifecycle.parseValue(1, AttemptLifecycle.Execution.class, execution),
                AttemptLifecycle.parseValue(1, AttemptLifecycle.AnswerCollection.class, answers));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        byte[] bytes = row.getBytes(column);
        require(bytes != null && bytes.length == 16, CORRUPT_EVIDENCE);
        var buffer = ByteBuffer.wrap(bytes); return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static MapSqlParameterSource parameters(UUID attemptId) {
        return new MapSqlParameterSource("attempt", ByteBuffer.allocate(16)
                .putLong(attemptId.getMostSignificantBits()).putLong(attemptId.getLeastSignificantBits()).array());
    }
}
