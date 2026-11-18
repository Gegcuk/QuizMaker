package uk.gegc.quizmaker.features.result.infra.repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.result.domain.model.ResultRelease;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfiguration;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.infra.release.ReleaseConfigurationFormat;
import uk.gegc.quizmaker.features.result.domain.repository.ResultReleaseRepository;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcResultReleaseRepository implements ResultReleaseRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ReleaseConfigurationFormat configurationFormat = new ReleaseConfigurationFormat();
    public JdbcResultReleaseRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<UUID> submission(UUID attemptId) {
        return jdbc.query("SELECT BIN_TO_UUID(id) id FROM attempt_submission WHERE attempt_id=UUID_TO_BIN(:id) FOR SHARE",
                Map.of("id", attemptId.toString()), (r, n) -> UUID.fromString(r.getString("id"))).stream().findFirst();
    }
    @Override public Optional<ResultRelease> find(UUID resultId) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(r.id) id,r.schema_version,r.released_at,t.event_type,t.actor_type,t.reason_code,
                    BIN_TO_UUID(t.command_id) command_id,t.item_id,t.from_execution,t.to_execution,
                    t2.policy_snapshot_id=a.policy_snapshot_id AND t.form_id=a.form_id
                    AND t.attempt_id=a.attempt_id AS binding_matches
                FROM attempt_result_release r JOIN attempt_automatic_result a ON a.id=r.result_id
                JOIN attempt_trace_event t ON t.event_id=r.id
                JOIN attempt_trace_stream t2 ON t2.attempt_id=a.attempt_id
                WHERE r.result_id=UUID_TO_BIN(:id) FOR SHARE
                """, Map.of("id", resultId.toString()), (r, n) -> {
            require("RESULT_RELEASED".equals(r.getString("event_type")) && "SYSTEM".equals(r.getString("actor_type"))
                    && "RELEASED".equals(r.getString("reason_code")) && resultId.toString().equals(r.getString("command_id"))
                    && r.getBytes("item_id") == null && r.getString("from_execution") == null
                    && r.getString("to_execution") == null && r.getBoolean("binding_matches"), CORRUPT);
            return new ResultRelease(UUID.fromString(r.getString("id")), r.getInt("schema_version"), resultId,
                    r.getObject("released_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        }).stream().findFirst();
    }
    @Override public boolean scheduled(UUID resultId) {
        try {
            return !jdbc.query("SELECT 1 FROM attempt_scheduled_release WHERE result_id=UUID_TO_BIN(:id)",
                    Map.of("id",resultId.toString()), (r,n)->true).isEmpty();
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        }
    }
    @Override public Optional<ReleaseConfiguration> scheduledRule(UUID resultId) {
        try {
            return jdbc.query("""
                    SELECT c.canonical_configuration,c.configuration_hash,j.configuration_hash captured_hash,j.due_at,
                        j.schema_version=1 AND c.revision_id=r.quiz_revision_id AND c.quiz_id=q.quiz_id
                        AND ((j.work_state='RELEASED' AND published.id=j.event_id AND published.released_at>=j.due_at)
                            OR (j.work_state<>'RELEASED' AND published.id IS NULL)) AS consistent
                    FROM attempt_scheduled_release j
                    LEFT JOIN result_release_configurations c ON c.id=j.configuration_id
                    JOIN attempt_automatic_result r ON r.id=j.result_id
                    LEFT JOIN attempt_result_release published ON published.result_id=r.id
                    JOIN quiz_revisions q ON q.id=r.quiz_revision_id WHERE j.result_id=UUID_TO_BIN(:id)
                    """,Map.of("id",resultId.toString()),(r,n)->{
                require(r.getBoolean("consistent") && Objects.equals(r.getString("configuration_hash"),r.getString("captured_hash")),CORRUPT);
                var rule=configurationFormat.decode(r.getString("canonical_configuration"),r.getString("captured_hash"));
                require(rule.family()==ResultDisclosure.Family.FORMAL_ASSESSMENT
                        && rule.triggers().equals(Set.of(ReleaseConfiguration.Trigger.SCHEDULED))
                        && rule.schedule().instant().equals(r.getObject("due_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),UNSUPPORTED);
                return rule;
            }).stream().findFirst();
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        }
    }
    @Override public void insert(ResultRelease release) {
        jdbc.update("""
                INSERT INTO attempt_result_release(id,schema_version,result_id,released_at)
                VALUES(UUID_TO_BIN(:id),:version,UUID_TO_BIN(:result),:at)
                """, Map.of("id", release.id().toString(), "version", release.version(), "result", release.resultId().toString(),
                "at", LocalDateTime.ofInstant(release.releasedAt(), ZoneOffset.UTC)));
    }
}
