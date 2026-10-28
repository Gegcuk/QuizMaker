package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException;
import uk.gegc.quizmaker.features.attempt.domain.repository.ExecutionTraceRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcExecutionTraceRepository implements ExecutionTraceRepository {
    private static final String SELECT = "SELECT e.*, s.policy_snapshot_id FROM attempt_trace_event e ";
    private static final String JOIN_SCOPE = """
            JOIN attempt_trace_stream s ON s.attempt_id=e.attempt_id
            """;
    // The FK index orders by form before sequence; cursor/tail reads must use the sequence key.
    private static final String SELECT_SEQUENCE = SELECT + " FORCE INDEX (PRIMARY) " + JOIN_SCOPE;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcExecutionTraceRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void ensureStream(Scope scope) {
        // Only existing compatible target roots may acquire a stream. No target state is inferred.
        jdbc.update("""
                INSERT INTO attempt_trace_stream (attempt_id,schema_version,policy_snapshot_id,form_id,last_sequence)
                SELECT :attempt,1,:policy,:form,0 FROM attempt_lifecycle_state l
                JOIN attempt_participant_subject u ON u.attempt_id=l.attempt_id
                JOIN attempt_policy_snapshot p ON p.id=:policy
                JOIN attempt_form f ON f.id=:form AND f.quiz_revision_id=p.quiz_revision_id
                WHERE l.attempt_id=:attempt
                ON DUPLICATE KEY UPDATE attempt_id=attempt_trace_stream.attempt_id
                """, parameters(scope));
    }

    @Override
    public Optional<Stream> lockStream(UUID attemptId) {
        return jdbc.query("SELECT * FROM attempt_trace_stream WHERE attempt_id=:attempt FOR UPDATE",
                new MapSqlParameterSource("attempt", bytes(attemptId)), (row, index) ->
                        new Stream(row.getInt("schema_version"), scope(row), row.getLong("last_sequence"))).stream().findFirst();
    }

    @Override
    public Identity reserve(UUID attemptId, UUID eventId) {
        var parameters = new MapSqlParameterSource("attempt", bytes(attemptId)).addValue("event", bytes(eventId));
        jdbc.update("""
                INSERT INTO attempt_trace_identity (event_id,attempt_id,sequence_number) VALUES (:event,:attempt,0)
                ON DUPLICATE KEY UPDATE event_id=attempt_trace_identity.event_id
                """, parameters);
        return jdbc.queryForObject("SELECT attempt_id,sequence_number FROM attempt_trace_identity WHERE event_id=:event FOR UPDATE",
                parameters, (row, index) -> new Identity(uuid(row, "attempt_id"), row.getLong("sequence_number")));
    }

    @Override
    public boolean assign(UUID eventId, long sequence) {
        return jdbc.update("UPDATE attempt_trace_identity SET sequence_number=:sequence WHERE event_id=:event AND sequence_number=0",
                new MapSqlParameterSource("event", bytes(eventId)).addValue("sequence", sequence)) == 1;
    }

    @Override
    public Optional<Stored> findEvent(UUID eventId) {
        // Current read is essential when the caller already has an old REPEATABLE_READ snapshot.
        return jdbc.query(SELECT + JOIN_SCOPE + " WHERE e.event_id=:event FOR SHARE",
                new MapSqlParameterSource("event", bytes(eventId)), (row, index) -> stored(row)).stream().findFirst();
    }

    @Override
    public Optional<Stored> last(UUID attemptId) {
        // A bounded tail read detects a counter behind/ahead of persisted evidence.
        return jdbc.query(SELECT_SEQUENCE + " WHERE e.attempt_id=:attempt ORDER BY e.sequence_number DESC LIMIT 1 FOR SHARE",
                new MapSqlParameterSource("attempt", bytes(attemptId)), (row, index) -> stored(row)).stream().findFirst();
    }

    @Override
    public void insert(Stored saved) {
        var event = saved.event();
        var data = event.data();
        jdbc.update("""
                INSERT INTO attempt_trace_event (attempt_id,sequence_number,event_id,schema_version,event_type,
                  actor_type,recorded_at,command_id,form_id,item_id,from_execution,from_answers,to_execution,to_answers,reason_code)
                VALUES (:attempt,:sequence,:event,:version,:type,:actor,:at,:command,:form,:item,:fromExecution,:fromAnswers,
                  :toExecution,:toAnswers,:reason)
                """, parameters(saved.scope()).addValue("sequence", saved.sequence()).addValue("event", bytes(event.id()))
                .addValue("version", event.version()).addValue("type", event.type().name()).addValue("actor", event.actor().name())
                .addValue("at", LocalDateTime.ofInstant(saved.recordedAt(), ZoneOffset.UTC)).addValue("command", bytes(data.commandId()))
                .addValue("item", bytes(data.itemId())).addValue("fromExecution", data.from() == null ? null : data.from().execution().name())
                .addValue("fromAnswers", data.from() == null ? null : data.from().answers().name())
                .addValue("toExecution", data.to() == null ? null : data.to().execution().name())
                .addValue("toAnswers", data.to() == null ? null : data.to().answers().name()).addValue("reason", data.reason().name()));
    }

    @Override
    public boolean advance(UUID attemptId, long expectedSequence) {
        return jdbc.update("""
                UPDATE attempt_trace_stream SET last_sequence=last_sequence+1
                WHERE attempt_id=:attempt AND last_sequence=:expected AND schema_version=1
                """, new MapSqlParameterSource("attempt", bytes(attemptId)).addValue("expected", expectedSequence)) == 1;
    }

    @Override
    public List<Stored> page(UUID attemptId, long after, int limit) {
        require(attemptId != null, INVALID_INPUT);
        ExecutionTrace.page(after, limit);
        var result = jdbc.query(SELECT_SEQUENCE + " WHERE e.attempt_id=:attempt AND e.sequence_number>:after ORDER BY e.sequence_number LIMIT :limit",
                new MapSqlParameterSource("attempt", bytes(attemptId)).addValue("after", after).addValue("limit", limit),
                (row, index) -> stored(row));
        if (!result.isEmpty()) require(result.get(0).sequence() == after + 1, CORRUPT_TRACE);
        for (int i = 1; i < result.size(); i++) {
            require(result.get(i).sequence() == result.get(i - 1).sequence() + 1, CORRUPT_TRACE);
        }
        return List.copyOf(result);
    }

    private static Stored stored(ResultSet row) throws SQLException {
        int version = row.getInt("schema_version");
        if (version != ExecutionTrace.VERSION) throw new TraceException(UNSUPPORTED_VERSION);
        try {
            return new Stored(scope(row), row.getLong("sequence_number"),
                    row.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    new Event(version, uuid(row, "event_id"), ExecutionTrace.parse(version, Type.class, row.getString("event_type")),
                            ExecutionTrace.parse(version, Actor.class, row.getString("actor_type")),
                            new Data(uuid(row, "command_id"), uuid(row, "item_id"), state(row, "from"), state(row, "to"),
                                    ExecutionTrace.parse(version, ReasonCode.class, row.getString("reason_code")))));
        } catch (RuntimeException failure) { throw new TraceException(CORRUPT_TRACE); }
    }

    private static State state(ResultSet row, String prefix) throws SQLException {
        String execution = row.getString(prefix + "_execution");
        String answers = row.getString(prefix + "_answers");
        if (execution == null && answers == null) return null;
        return new State(1, AttemptLifecycle.parseValue(1, AttemptLifecycle.Execution.class, execution),
                AttemptLifecycle.parseValue(1, AttemptLifecycle.AnswerCollection.class, answers));
    }

    private static Scope scope(ResultSet row) throws SQLException {
        return new Scope(uuid(row, "attempt_id"), uuid(row, "policy_snapshot_id"), uuid(row, "form_id"));
    }

    private static MapSqlParameterSource parameters(Scope scope) {
        return new MapSqlParameterSource("attempt", bytes(scope.attemptId())).addValue("policy", bytes(scope.policySnapshotId()))
                .addValue("form", bytes(scope.formId()));
    }

    private static byte[] bytes(UUID id) {
        return id == null ? null : ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        var bytes = row.getBytes(column);
        if (bytes == null) return null;
        require(bytes.length == 16, CORRUPT_TRACE);
        var value = ByteBuffer.wrap(bytes);
        return new UUID(value.getLong(), value.getLong());
    }
}
