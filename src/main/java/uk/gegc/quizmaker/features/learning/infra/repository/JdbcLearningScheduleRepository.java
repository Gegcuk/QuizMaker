package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository;
import uk.gegc.quizmaker.features.learning.infra.scheduling.FsrsStateFormat;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLearningScheduleRepository implements LearningScheduleRepository {
    private static final String KEY = "learner_id=:learner AND quiz_id=:quiz AND quiz_revision_id=:revision AND question_revision_id=:question";
    private final NamedParameterJdbcTemplate jdbc;
    private final FsrsStateFormat format = new FsrsStateFormat();
    public JdbcLearningScheduleRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean sourceExists(Key key) {
        return safe(() -> !jdbc.query("""
                SELECT r.id FROM quiz_revisions r JOIN quiz_revision_items i ON i.quiz_revision_id=r.id
                WHERE r.id=:revision AND r.quiz_id=:quiz AND r.schema_version=:sourceVersion
                AND i.question_revision_id=:question LIMIT 1
                """, parameters(key).addValue("sourceVersion", key.source().revisionSchemaVersion()),
                (row, index) -> row.getBytes(1)).isEmpty());
    }

    @Override public Receipt reserve(Key key, UUID commandId, UUID reservationId, String input) {
        return safe(() -> {
            var p = parameters(key).addValue("command", bytes(commandId)).addValue("reservation", bytes(reservationId))
                    .addValue("input", input);
            jdbc.update("""
                INSERT INTO learning_schedule_receipt(learner_id,command_id,reservation_id,semantic_input)
                VALUES(:learner,:command,:reservation,:input)
                ON DUPLICATE KEY UPDATE command_id=learning_schedule_receipt.command_id
                """, p);
            return jdbc.queryForObject("""
                SELECT reservation_id,semantic_input,result_cycle_version,result_state FROM learning_schedule_receipt
                WHERE learner_id=:learner AND command_id=:command FOR UPDATE
                """, p, (row, index) -> {
                    String state = row.getString("result_state");
                    Long version = row.getObject("result_cycle_version", Long.class);
                    require((version == null) == (state == null), CORRUPT);
                    return new Receipt(uuid(row, "reservation_id"), row.getString("semantic_input"),
                            state == null ? null : snapshot(version, state));
                });
        });
    }

    @Override public void complete(Key key, UUID commandId, UUID reservationId, Snapshot result) {
        safe(() -> {
            require(jdbc.update("""
                UPDATE learning_schedule_receipt SET result_cycle_version=:version,result_state=:state
                WHERE learner_id=:learner AND command_id=:command AND reservation_id=:reservation AND result_state IS NULL
                """, parameters(key).addValue("command", bytes(commandId)).addValue("reservation", bytes(reservationId))
                .addValue("version", result.cycleVersion()).addValue("state", format.encodeState(result.state()))) == 1, CORRUPT);
            return null;
        });
    }

    @Override public Optional<Snapshot> lock(Key key) {
        return lockCycle(key).map(cycle -> find(key).orElseThrow(() ->
                new uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException(CORRUPT)));
    }

    @Override public Optional<ActiveCycle> readCycle(Key key) {
        return safe(() -> jdbc.query("""
                SELECT s.active_cycle_id,s.cycle_version,c.id,c.strategy_id
                FROM learning_schedule_slot s
                LEFT JOIN learning_schedule_cycle c ON c.id=s.active_cycle_id
                    AND c.learner_id=s.learner_id AND c.quiz_id=s.quiz_id
                    AND c.quiz_revision_id=s.quiz_revision_id AND c.question_revision_id=s.question_revision_id
                    AND c.cycle_version=s.cycle_version
                WHERE s.learner_id=:learner AND s.quiz_id=:quiz AND s.quiz_revision_id=:revision
                    AND s.question_revision_id=:question
                FOR SHARE OF s
                """, parameters(key), (row, index) -> {
            require(row.getBytes("active_cycle_id") != null && row.getBytes("id") != null
                    && row.getLong("cycle_version") > 0, CORRUPT);
            return new ActiveCycle(uuid(row,"id"),row.getLong("cycle_version"),row.getString("strategy_id"));
        }).stream().findFirst());
    }

    @Override public Optional<ActiveCycle> lockCycle(Key key) {
        return safe(() -> {
            UUID seed = UUID.randomUUID();
            var p = parameters(key).addValue("seed", bytes(seed));
            jdbc.update("""
                INSERT INTO learning_schedule_slot(learner_id,quiz_id,quiz_revision_id,question_revision_id,cycle_version,seed_id)
                VALUES(:learner,:quiz,:revision,:question,0,:seed)
                ON DUPLICATE KEY UPDATE seed_id=learning_schedule_slot.seed_id
                """, p);
            var active = jdbc.queryForObject(
                    "SELECT active_cycle_id,cycle_version,seed_id FROM learning_schedule_slot WHERE " + KEY + " FOR UPDATE", p,
                    (row, index) -> {
                        boolean present = row.getBytes("active_cycle_id") != null;
                        require(present || row.getLong("cycle_version") == 0 && seed.equals(uuid(row, "seed_id")), CORRUPT);
                        return present ? new ActiveCycle(uuid(row, "active_cycle_id"), row.getLong("cycle_version"), "") : null;
                    });
            if (active == null) return Optional.empty();
            return Optional.of(jdbc.queryForObject("""
                    SELECT id,cycle_version,strategy_id FROM learning_schedule_cycle
                    WHERE id=:cycle AND learner_id=:learner AND quiz_id=:quiz AND quiz_revision_id=:revision
                    AND question_revision_id=:question AND cycle_version=:version
                    """, p.addValue("cycle", bytes(active.id())).addValue("version", active.version()),
                    (row, index) -> new ActiveCycle(uuid(row, "id"), row.getLong("cycle_version"), row.getString("strategy_id"))));
        });
    }

    @Override public Optional<Snapshot> find(Key key) {
        return safe(() -> jdbc.query("""
                SELECT s.active_cycle_id,s.cycle_version,c.strategy_id,c.created_at,f.sequence,f.state,
                       e.transition,r.result_cycle_version AS initial_version,r.result_state AS initial_state
                FROM learning_schedule_slot s
                LEFT JOIN learning_schedule_cycle c ON c.id=s.active_cycle_id
                LEFT JOIN learning_fsrs_state f ON f.cycle_id=c.id
                LEFT JOIN learning_review_event e ON e.cycle_id=f.cycle_id AND e.sequence=f.sequence
                LEFT JOIN learning_schedule_receipt r ON r.learner_id=c.learner_id AND r.command_id=c.create_command_id
                WHERE s.learner_id=:learner AND s.quiz_id=:quiz AND s.quiz_revision_id=:revision AND s.question_revision_id=:question
                """, parameters(key), (row, index) -> LearningScheduleEvidence.read(row, key)).stream().findFirst());
    }

    @Override public void createCycle(Key key, Snapshot next, long expectedVersion, UUID commandId) {
        safe(() -> {
            replaceCycle(key, new ActiveCycle(next.state().identity().cycleId(), next.cycleVersion(), "fsrs/v1"),
                    expectedVersion, commandId, next.state().dueAt());
            var p = parameters(key).addValue("cycle", bytes(next.state().identity().cycleId()))
                    .addValue("state", format.encodeState(next.state()));
            jdbc.update("INSERT INTO learning_fsrs_state(cycle_id,sequence,state) VALUES(:cycle,0,:state)", p);
            return null;
        });
    }

    @Override public void replaceCycle(Key key, ActiveCycle next, long expectedVersion, UUID commandId, Instant createdAt) {
        safe(() -> {
            require(next != null && next.id() != null && expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE
                    && next.version() == expectedVersion + 1 && next.strategyId() != null
                    && next.strategyId().matches("[a-z][a-z0-9-]*/v[1-9][0-9]*") && next.strategyId().length() <= 64
                    && commandId != null && createdAt != null, INVALID_INPUT);
            var p = parameters(key).addValue("cycle", bytes(next.id())).addValue("version", next.version())
                    .addValue("previous", expectedVersion).addValue("strategy", next.strategyId())
                    .addValue("command", bytes(commandId)).addValue("created", LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            // Keep the shared CAS/finish-first invariant even when a later strategy invokes this
            // primitive directly after resolving its authorized scope.
            require(Long.valueOf(expectedVersion).equals(jdbc.queryForObject(
                    "SELECT cycle_version FROM learning_schedule_slot WHERE " + KEY + " FOR UPDATE", p, Long.class)), STALE_STATE);
            require(!hasCaptures(key), ACTIVE_SESSION);
            jdbc.update("""
                INSERT INTO learning_schedule_cycle(id,learner_id,quiz_id,quiz_revision_id,question_revision_id,
                    cycle_version,strategy_id,create_command_id,created_at)
                VALUES(:cycle,:learner,:quiz,:revision,:question,:version,:strategy,:command,:created)
                """, p);
            require(jdbc.update("UPDATE learning_schedule_slot SET active_cycle_id=:cycle,cycle_version=:version WHERE "
                    + KEY + " AND cycle_version=:previous", p) == 1, STALE_STATE);
            return null;
        });
    }

    @Override public void advance(Key key, Snapshot previous, Transition transition) {
        safe(() -> {
            require(previous.state().identity().equals(transition.state().identity())
                    && previous.state().sequence() == transition.review().expectedSequence(), CORRUPT);
            var p = parameters(key).addValue("cycle", bytes(transition.state().identity().cycleId()))
                    .addValue("command", bytes(transition.review().reviewId())).addValue("sequence", transition.state().sequence())
                    .addValue("previous", previous.state().sequence()).addValue("state", format.encodeState(transition.state()))
                    .addValue("transition", format.encodeTransition(transition));
            jdbc.update("""
                INSERT INTO learning_review_event(cycle_id,sequence,learner_id,command_id,transition)
                VALUES(:cycle,:sequence,:learner,:command,:transition)
                """, p);
            require(jdbc.update("UPDATE learning_fsrs_state SET sequence=:sequence,state=:state WHERE cycle_id=:cycle AND sequence=:previous", p) == 1, STALE_STATE);
            return null;
        });
    }

    @Override public boolean hasCaptures(Key key) {
        return safe(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM learning_cycle_session c JOIN learning_schedule_slot s ON s.active_cycle_id=c.cycle_id
                WHERE s.learner_id=:learner AND s.quiz_id=:quiz AND s.quiz_revision_id=:revision
                AND s.question_revision_id=:question AND c.released=0)
                """, parameters(key), Boolean.class)));
    }

    @Override public boolean sessionContains(Key key, UUID sessionId) {
        return safe(() -> !jdbc.query("""
                SELECT f.id FROM learning_session s JOIN learning_form f ON f.session_id=s.id
                JOIN learning_form_item i ON i.form_id=f.id
                WHERE s.id=:session AND s.learner_id=:learner AND s.quiz_id=:quiz AND s.quiz_revision_id=:revision
                AND i.question_revision_id=:question LIMIT 1
                """, parameters(key).addValue("session", bytes(sessionId)), (row, index) -> row.getBytes(1)).isEmpty());
    }

    @Override public void capture(Key key, Snapshot current, UUID sessionId) {
        safe(() -> {
            var p = parameters(key).addValue("cycle", bytes(current.state().identity().cycleId()))
                    .addValue("session", bytes(sessionId)).addValue("sequence", current.state().sequence());
            // The caller holds this question's slot lock. Release does not erase the session's
            // captured cycle: a new command must not move that binding after a restart.
            require(Boolean.FALSE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM learning_cycle_session s JOIN learning_schedule_cycle c ON c.id=s.cycle_id
                WHERE s.session_id=:session AND c.learner_id=:learner AND c.quiz_id=:quiz
                AND c.quiz_revision_id=:revision AND c.question_revision_id=:question
                AND (s.cycle_id<>:cycle OR s.released=1))
                """, p, Boolean.class)), SESSION_CLOSED);
            jdbc.update("""
                INSERT INTO learning_cycle_session(cycle_id,session_id,captured_sequence,released) VALUES(:cycle,:session,:sequence,0)
                ON DUPLICATE KEY UPDATE session_id=learning_cycle_session.session_id
                """, p);
            return null;
        });
    }

    @Override public void release(Key key, Snapshot current, UUID sessionId) {
        safe(() -> {
            require(jdbc.update("UPDATE learning_cycle_session SET released=1 WHERE cycle_id=:cycle AND session_id=:session AND released=0",
                    parameters(key).addValue("cycle", bytes(current.state().identity().cycleId())).addValue("session", bytes(sessionId))) == 1, SESSION_CLOSED);
            return null;
        });
    }

    private Snapshot snapshot(long version, String json) {
        require(version > 0 && json != null, CORRUPT);
        return new Snapshot(version, format.decodeState(json));
    }
    private static MapSqlParameterSource parameters(Key k) {
        return new MapSqlParameterSource("learner", bytes(k.learnerId())).addValue("quiz", bytes(k.source().quizId()))
                .addValue("revision", bytes(k.source().quizRevisionId())).addValue("question", bytes(k.questionRevisionId()));
    }
    private static UUID uuid(ResultSet row, String column) throws SQLException {
        byte[] value = row.getBytes(column); require(value != null && value.length == 16, CORRUPT);
        var buffer = ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    /** Discard driver/payload diagnostics before the MANDATORY transaction interceptor sees them. */
    private static <T> T safe(Supplier<T> work) {
        try { return work.get(); }
        catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
}
