package uk.gegc.quizmaker.features.result.infra.repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.result.domain.model.ResultRelease;
import uk.gegc.quizmaker.features.result.domain.repository.ResultReleaseRepository;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcResultReleaseRepository implements ResultReleaseRepository {
    private final NamedParameterJdbcTemplate jdbc;
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
    @Override public void insert(ResultRelease release) {
        jdbc.update("""
                INSERT INTO attempt_result_release(id,schema_version,result_id,released_at)
                VALUES(UUID_TO_BIN(:id),:version,UUID_TO_BIN(:result),:at)
                """, Map.of("id", release.id().toString(), "version", release.version(), "result", release.resultId().toString(),
                "at", LocalDateTime.ofInstant(release.releasedAt(), ZoneOffset.UTC)));
    }
}
