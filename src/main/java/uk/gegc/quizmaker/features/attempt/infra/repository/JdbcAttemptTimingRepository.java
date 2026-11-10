package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.timing.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptTimingRepository;
import static uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTimingException.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTimingException.Reason.*;

@Repository
public class JdbcAttemptTimingRepository implements AttemptTimingRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAttemptTimingRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(AttemptTiming value) {
        transaction();
        jdbc.update("""
                INSERT INTO attempt_timing (attempt_id,policy_snapshot_id,schema_version,started_at,
                    duration_seconds,duration_nanos,overall_deadline,duration_deadline,effective_deadline,grace_seconds)
                VALUES (:id,:snapshot,:version,:start,:seconds,:nanos,:overall,:duration,:effective,0)
                """, new MapSqlParameterSource("id", bytes(value.attemptId())).addValue("snapshot", bytes(value.policySnapshotId()))
                .addValue("version", value.version()).addValue("start", local(value.startedAt()))
                .addValue("seconds", value.policy().duration() == null ? null : value.policy().duration().getSeconds())
                .addValue("nanos", value.policy().duration() == null ? null : value.policy().duration().getNano())
                .addValue("overall", local(value.policy().overallDeadline())).addValue("duration", local(value.durationDeadline()))
                .addValue("effective", local(value.effectiveDeadline())));
    }
    @Override public Optional<AttemptTiming> read(UUID attemptId) {
        transaction();
        return jdbc.query("SELECT * FROM attempt_timing WHERE attempt_id=:id FOR SHARE",
                new MapSqlParameterSource("id", bytes(attemptId)), (row, index) -> decode(row)).stream().findFirst();
    }
    private static AttemptTiming decode(ResultSet row) throws SQLException {
        int version = row.getInt("schema_version"); require(version == 1, UNSUPPORTED);
        try {
            Long seconds = row.getObject("duration_seconds", Long.class);
            Integer nanos = row.getObject("duration_nanos", Integer.class);
            require((seconds == null) == (nanos == null), CORRUPT);
            require(seconds == null || seconds >= 0 && nanos >= 0 && nanos < 1_000_000_000
                    && nanos % 1_000 == 0 && (seconds > 0 || nanos > 0), CORRUPT);
            var policy = new TimingPolicy(TimingMode.TOTAL_ATTEMPT, seconds == null ? null : Duration.ofSeconds(seconds, nanos), time(row,"overall_deadline"));
            return new AttemptTiming(version, uuid(row.getBytes("attempt_id")), uuid(row.getBytes("policy_snapshot_id")),
                    policy, time(row,"started_at"), time(row,"duration_deadline"), time(row,"effective_deadline"), Duration.ofSeconds(row.getInt("grace_seconds")));
        } catch (AttemptTimingException known) { throw known; }
        catch (RuntimeException corrupt) { throw new AttemptTimingException(CORRUPT); }
    }
    private static void transaction() {
        // The owning timing service sanitizes SQL failures before its transaction interceptor.
        // A second repository transaction interceptor would log the raw diagnostic first.
        require(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(), UNAVAILABLE);
    }
    private static LocalDateTime local(Instant v) { return v == null ? null : LocalDateTime.ofInstant(v, ZoneOffset.UTC); }
    private static Instant time(ResultSet row, String name) throws SQLException {
        var value = row.getObject(name, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private static byte[] bytes(UUID value) {
        require(value != null, INVALID);
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] value) {
        require(value != null && value.length == 16, CORRUPT);
        var bytes = ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }
}
