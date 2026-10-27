package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;
import uk.gegc.quizmaker.features.attempt.domain.repository.LifecycleStateRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLifecycleStateRepository implements LifecycleStateRepository {
    private static final String SELECT = "SELECT * FROM attempt_lifecycle_state WHERE attempt_id=:id";
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLifecycleStateRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void insert(LifecycleState value) {
        jdbc.update("""
                INSERT INTO attempt_lifecycle_state
                  (attempt_id,lifecycle_version,row_version,execution_state,answer_collection_state,
                   created_at,updated_at,started_at,submitted_at)
                VALUES (:id,:contract,:version,:execution,:answers,:created,:updated,:started,:submitted)
                """, parameters(value));
    }

    @Override
    public Optional<StoredState> findById(UUID attemptId) { return find(attemptId, false); }

    @Override
    public Optional<StoredState> findByIdForUpdate(UUID attemptId) { return find(attemptId, true); }

    private Optional<StoredState> find(UUID id, boolean lock) {
        return jdbc.query(SELECT + (lock ? " FOR UPDATE" : ""), new MapSqlParameterSource("id", bytes(id)),
                (row, index) -> new StoredState(uuid(row), row.getInt("lifecycle_version"), row.getLong("row_version"),
                        row.getString("execution_state"), row.getString("answer_collection_state"),
                        time(row, "created_at"), time(row, "updated_at"), time(row, "started_at"), time(row, "submitted_at")))
                .stream().findFirst();
    }

    @Override
    public boolean compareAndSet(long expectedVersion, LifecycleState next) {
        return jdbc.update("""
                UPDATE attempt_lifecycle_state SET row_version=:version, execution_state=:execution,
                  answer_collection_state=:answers, updated_at=:updated, started_at=:started, submitted_at=:submitted
                WHERE attempt_id=:id AND row_version=:expected AND lifecycle_version=:contract
                """, parameters(next).addValue("expected", expectedVersion)) == 1;
    }

    private static MapSqlParameterSource parameters(LifecycleState value) {
        return new MapSqlParameterSource("id", bytes(value.attemptId())).addValue("contract", value.state().version())
                .addValue("version", value.rowVersion()).addValue("execution", value.state().execution().name())
                .addValue("answers", value.state().answers().name()).addValue("created", local(value.createdAt()))
                .addValue("updated", local(value.updatedAt())).addValue("started", local(value.startedAt()))
                .addValue("submitted", local(value.submittedAt()));
    }

    private static LocalDateTime local(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }

    private static Instant time(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(ResultSet row) throws SQLException {
        var value = ByteBuffer.wrap(row.getBytes("attempt_id"));
        return new UUID(value.getLong(), value.getLong());
    }
}
