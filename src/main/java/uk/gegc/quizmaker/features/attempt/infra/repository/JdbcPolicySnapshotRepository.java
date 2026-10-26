package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException;
import uk.gegc.quizmaker.features.attempt.domain.repository.PolicySnapshotRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException.Reason.SNAPSHOT_UNAVAILABLE;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcPolicySnapshotRepository implements PolicySnapshotRepository {
    private static final String SELECT = """
            SELECT s.* FROM attempt_policy_snapshot s JOIN quiz_revisions q ON q.id=s.quiz_revision_id
            WHERE s.id=:id AND q.owner_id=:owner
            """;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPolicySnapshotRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public StoredSnapshot createOrReplay(UUID revisionOwnerId, StoredSnapshot value) {
        if (revisionOwnerId == null) throw new PolicySnapshotException(SNAPSHOT_UNAVAILABLE);
        var parameters = parameters(revisionOwnerId, value.id())
                .addValue("revision", bytes(value.quizRevisionId())).addValue("schema", value.schemaVersion())
                .addValue("resolver", value.resolverVersion()).addValue("canonical", value.canonicalVersion())
                .addValue("algorithm", value.hashAlgorithm()).addValue("json", value.canonicalJson()).addValue("hash", value.hash());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM quiz_revisions WHERE id=:revision AND owner_id=:owner AND schema_version=1)
                """, parameters, Boolean.class))) throw new PolicySnapshotException(SNAPSHOT_UNAVAILABLE);
        // The unique ID serializes competing inserts. No existing evidence is overwritten.
        jdbc.update("""
                INSERT INTO attempt_policy_snapshot
                  (id,schema_version,quiz_revision_id,resolver_version,canonical_version,hash_algorithm,canonical_json,policy_hash)
                VALUES (:id,:schema,:revision,:resolver,:canonical,:algorithm,:json,:hash)
                ON DUPLICATE KEY UPDATE id=attempt_policy_snapshot.id
                """, parameters);
        // Current read also sees a concurrent winner under an older REPEATABLE_READ snapshot.
        return jdbc.query(SELECT + " FOR UPDATE", parameters, (row, index) -> stored(row)).stream().findFirst()
                .orElseThrow(() -> new PolicySnapshotException(SNAPSHOT_UNAVAILABLE));
    }

    @Override
    public Optional<StoredSnapshot> findById(UUID revisionOwnerId, UUID snapshotId) {
        if (revisionOwnerId == null || snapshotId == null) throw new PolicySnapshotException(SNAPSHOT_UNAVAILABLE);
        return jdbc.query(SELECT, parameters(revisionOwnerId, snapshotId), (row, index) -> stored(row)).stream().findFirst();
    }

    private static StoredSnapshot stored(ResultSet row) throws SQLException {
        return new StoredSnapshot(uuid(row, "id"), row.getInt("schema_version"), uuid(row, "quiz_revision_id"),
                row.getInt("resolver_version"), row.getInt("canonical_version"), row.getString("hash_algorithm"),
                row.getString("canonical_json"), row.getString("policy_hash"));
    }

    private static MapSqlParameterSource parameters(UUID owner, UUID id) {
        return new MapSqlParameterSource("owner", bytes(owner)).addValue("id", bytes(id));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        var buffer = ByteBuffer.wrap(row.getBytes(column));
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
