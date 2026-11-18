package uk.gegc.quizmaker.features.result.infra.repository;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.result.application.ScheduledResultReleaseService.Cursor;
import uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.Binding;
import uk.gegc.quizmaker.features.result.domain.model.release.ScheduledRelease;
import uk.gegc.quizmaker.features.result.domain.repository.ScheduledReleaseRepository;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcScheduledReleaseRepository implements ScheduledReleaseRepository {
    private final NamedParameterJdbcTemplate sql;
    public JdbcScheduledReleaseRepository(NamedParameterJdbcTemplate sql) { this.sql = sql; }

    @Override public Source lockSource(UUID submissionId) {
        return safe(() -> sql.query("""
                SELECT BIN_TO_UUID(r.id) result_id,BIN_TO_UUID(r.attempt_id) attempt_id,
                    BIN_TO_UUID(r.form_id) form_id,BIN_TO_UUID(r.policy_snapshot_id) policy_id,
                    BIN_TO_UUID(r.quiz_revision_id) revision_id,r.policy_hash,r.created_at,
                    BIN_TO_UUID(q.quiz_id) quiz_id,BIN_TO_UUID(q.owner_id) owner_id,
                    BIN_TO_UUID(u.user_id) user_id,
                    r.schema_version=1 AND r.scoring_contract='binary-result/v1' AND s.schema_version=1
                    AND i.schema_version=1 AND p.schema_version=1 AND p.resolver_version=1 AND p.canonical_version=1
                    AND p.hash_algorithm='SHA-256' AND q.schema_version=1 AND f.schema_version=1
                    AND t.schema_version=1 AND u.schema_version=1 AND u.subject_type='AUTHENTICATED_USER'
                    AND l.lifecycle_version=1 AND l.execution_state='SUBMITTED' AND l.answer_collection_state='COMPLETE'
                    AND l.row_version=s.state_version AND l.submitted_at=s.submitted_at
                    AND r.created_at>=s.submitted_at AND r.attempt_id=s.attempt_id
                    AND r.form_id=s.form_id AND r.policy_snapshot_id=s.policy_snapshot_id
                    AND r.quiz_revision_id=s.quiz_revision_id AND r.policy_hash=p.policy_hash
                    AND t.form_id=r.form_id AND t.policy_snapshot_id=r.policy_snapshot_id
                    AND f.quiz_revision_id=r.quiz_revision_id AND p.quiz_revision_id=r.quiz_revision_id
                    AND e.schema_version=1 AND e.created_at=r.created_at AND g.schema_version=1
                    AND g.event_type='AUTOMATIC_GRADE_PRODUCED' AND g.actor_type='SYSTEM' AND g.reason_code='GRADED'
                    AND g.attempt_id=r.attempt_id AND g.form_id=r.form_id AND g.command_id=s.command_id AS consistent
                FROM attempt_finalization_intent i
                JOIN attempt_submission s ON s.id=i.submission_id AND s.attempt_id=i.attempt_id
                JOIN attempt_lifecycle_state l ON l.attempt_id=s.attempt_id
                JOIN attempt_automatic_result r ON r.submission_id=s.id
                JOIN attempt_policy_snapshot p ON p.id=r.policy_snapshot_id
                JOIN quiz_revisions q ON q.id=r.quiz_revision_id
                JOIN attempt_form f ON f.id=r.form_id
                JOIN attempt_trace_stream t ON t.attempt_id=r.attempt_id
                JOIN attempt_participant_subject u ON u.attempt_id=r.attempt_id
                JOIN attempt_result_created e ON e.result_id=r.id
                JOIN attempt_trace_event g ON g.event_id=r.id
                WHERE i.submission_id=UUID_TO_BIN(:id) FOR UPDATE OF i,l
                """, id(submissionId), (r, n) -> {
            require(r.getBoolean("consistent"), CORRUPT);
            return new Source(submissionId, uuid(r,"result_id"), new Binding(uuid(r,"attempt_id"),uuid(r,"form_id"),
                    uuid(r,"policy_id"),uuid(r,"revision_id"),r.getString("policy_hash")),uuid(r,"quiz_id"),
                    uuid(r,"owner_id"),uuid(r,"user_id"), time(r,"created_at"));
        }).stream().findFirst().orElseThrow(() -> new ResultReleaseException(NOT_READY)));
    }
    @Override public Optional<ScheduledRelease> find(UUID submissionId, boolean lock) {
        return safe(() -> sql.query("""
                SELECT BIN_TO_UUID(submission_id) submission,BIN_TO_UUID(result_id) result,
                    BIN_TO_UUID(configuration_id) configuration,BIN_TO_UUID(event_id) event,configuration_hash,
                    schema_version,due_at,registered_at,work_state,failures,row_version,next_at,last_failure
                FROM attempt_scheduled_release WHERE submission_id=UUID_TO_BIN(:id)
                """ + (lock ? " FOR UPDATE" : ""), id(submissionId), (r,n) -> {
            require(r.getInt("schema_version")==1,UNSUPPORTED);
            return new ScheduledRelease(uuid(r,"submission"),
                uuid(r,"result"),uuid(r,"configuration"),r.getString("configuration_hash"),uuid(r,"event"),
                time(r,"due_at"),time(r,"registered_at"), ScheduledRelease.State.valueOf(r.getString("work_state")),
                r.getInt("failures"),r.getLong("row_version"),time(r,"next_at"), r.getString("last_failure") == null
                    ? null : ResultReleaseException.Reason.valueOf(r.getString("last_failure")));
        }).stream().findFirst());
    }
    @Override public void insert(ScheduledRelease w) {
        safe(() -> sql.update("""
                INSERT INTO attempt_scheduled_release(submission_id,schema_version,result_id,configuration_id,configuration_hash,
                    event_id,due_at,registered_at,work_state,failures,row_version,next_at,last_failure)
                VALUES(UUID_TO_BIN(:id),1,UUID_TO_BIN(:result),UUID_TO_BIN(:configuration),:hash,UUID_TO_BIN(:event),
                    :due,:registered,'PENDING',0,0,:due,NULL)
                """, id(w.submissionId()).addValue("result",w.resultId().toString()).addValue("configuration",w.configurationId().toString())
                .addValue("hash",w.configurationHash()).addValue("event",w.eventId().toString())
                .addValue("due",utc(w.dueAt())).addValue("registered",utc(w.registeredAt()))));
    }
    @Override public void update(ScheduledRelease previous, ScheduledRelease next) {
        safe(() -> {
            require(sql.update("""
                    UPDATE attempt_scheduled_release SET work_state=:state,failures=:failures,row_version=:next,
                        next_at=:at,last_failure=:reason WHERE submission_id=UUID_TO_BIN(:id) AND row_version=:previous
                    """, id(previous.submissionId()).addValue("state",next.state().name()).addValue("failures",next.failures())
                    .addValue("next",next.rowVersion()).addValue("at",utc(next.nextAt()))
                    .addValue("reason",next.lastFailure()==null?null:next.lastFailure().name())
                    .addValue("previous",previous.rowVersion())) == 1, CORRUPT);
            return null;
        });
    }
    @Override public List<Cursor> due(Instant now, int limit, Cursor after) {
        return safe(() -> sql.query("""
                SELECT next_at,BIN_TO_UUID(submission_id) submission FROM attempt_scheduled_release FORCE INDEX (ix_scheduled_due)
                WHERE work_state IN ('PENDING','RETRY') AND next_at<=:now
                """ + (after == null ? "" : " AND (next_at>:after OR (next_at=:after AND submission_id>UUID_TO_BIN(:id)))")
                + " ORDER BY next_at,submission_id LIMIT :limit", new MapSqlParameterSource("now",utc(now)).addValue("limit",limit)
                .addValue("after",after==null?null:utc(after.nextAt())).addValue("id",after==null?null:after.submissionId().toString()),
                (r,n) -> new Cursor(time(r,"next_at"),uuid(r,"submission"))));
    }
    @Override public Backlog backlog(Instant now) {
        return safe(() -> sql.queryForObject("""
                SELECT COUNT(*) total,MIN(next_at) oldest FROM attempt_scheduled_release
                WHERE work_state IN ('PENDING','RETRY') AND next_at<=:now
                """, Map.of("now",utc(now)), (r,n) -> new Backlog(r.getLong("total"),time(r,"oldest"))));
    }
    private static MapSqlParameterSource id(UUID id) { return new MapSqlParameterSource("id",id.toString()); }
    private static UUID uuid(ResultSet r,String name)throws SQLException { return UUID.fromString(r.getString(name)); }
    private static Instant time(ResultSet r,String name)throws SQLException {
        var value=r.getObject(name,LocalDateTime.class); return value==null?null:value.toInstant(ZoneOffset.UTC);
    }
    private static LocalDateTime utc(Instant at) { return at==null?null:LocalDateTime.ofInstant(at,ZoneOffset.UTC); }
    private static <T> T safe(Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
        catch (ResultReleaseException failure) { throw new ResultReleaseException(failure.reason()); }
        catch (RuntimeException failure) { throw new ResultReleaseException(CORRUPT); }
    }
}
