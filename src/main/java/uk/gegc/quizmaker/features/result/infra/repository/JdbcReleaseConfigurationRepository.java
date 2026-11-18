package uk.gegc.quizmaker.features.result.infra.repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationService.*;
import uk.gegc.quizmaker.features.result.domain.repository.ReleaseConfigurationRepository;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException;
import uk.gegc.quizmaker.features.result.infra.release.ReleaseConfigurationFormat;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.Reason.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcReleaseConfigurationRepository implements ReleaseConfigurationRepository {
    private static final String FIELDS = "BIN_TO_UUID(c.id) id, BIN_TO_UUID(c.quiz_id) quiz_id, BIN_TO_UUID(c.revision_id) revision_id, c.publication_version, c.configuration_hash, c.canonical_configuration, c.published_at";
    private final NamedParameterJdbcTemplate sql;
    private final ReleaseConfigurationFormat format = new ReleaseConfigurationFormat();
    public JdbcReleaseConfigurationRepository(NamedParameterJdbcTemplate sql) { this.sql = sql; }
    @Override public Optional<Publication> current(UUID quiz, UUID revision) {
        return safe(() -> sql.query("SELECT " + FIELDS + " FROM result_release_configuration_heads h JOIN result_release_configurations c ON c.id=h.configuration_id WHERE h.quiz_id=UUID_TO_BIN(:quiz) AND h.revision_id=UUID_TO_BIN(:revision)",
                ids(quiz, revision), (rs, n) -> publication(rs)).stream().findFirst());
    }
    @Override public Optional<Publication> read(UUID quiz, UUID revision, UUID id) {
        return safe(() -> sql.query("SELECT " + FIELDS + " FROM result_release_configurations c WHERE c.quiz_id=UUID_TO_BIN(:quiz) AND c.revision_id=UUID_TO_BIN(:revision) AND c.id=UUID_TO_BIN(:id)",
                ids(quiz, revision).addValue("id", id.toString()), (rs, n) -> publication(rs)).stream().findFirst());
    }
    @Override public Optional<Receipt> receipt(Command command, UUID actor) {
        return safe(() -> sql.query("SELECT r.request_hash, " + FIELDS + " FROM result_release_configuration_receipts r JOIN result_release_configurations c ON c.id=r.configuration_id WHERE r.quiz_id=UUID_TO_BIN(:quiz) AND r.revision_id=UUID_TO_BIN(:revision) AND r.actor_id=UUID_TO_BIN(:actor) AND r.command_id=UUID_TO_BIN(:command)",
                ids(command.quizId(), command.revisionId()).addValue("actor", actor.toString()).addValue("command", command.commandId().toString()),
                (rs, n) -> new Receipt(rs.getString("request_hash"), publication(rs))).stream().findFirst());
    }
    @Override public Optional<Publication> readBound(UUID owner, UUID quiz, UUID revision, UUID id) {
        return safe(() -> sql.query("SELECT " + FIELDS + " FROM result_release_configurations c JOIN quiz_revisions q ON q.id=c.revision_id WHERE q.owner_id=UUID_TO_BIN(:owner) AND c.quiz_id=UUID_TO_BIN(:quiz) AND c.revision_id=UUID_TO_BIN(:revision) AND c.id=UUID_TO_BIN(:id)",
                ids(quiz, revision).addValue("owner", owner.toString()).addValue("id", id.toString()),
                (rs, n) -> publication(rs)).stream().findFirst());
    }
    @Override public void insert(Publication p, UUID actor) {
        safe(() -> sql.update("""
                INSERT INTO result_release_configurations(id,quiz_id,revision_id,publication_version,configuration_hash,canonical_configuration,published_by,published_at)
                VALUES(UUID_TO_BIN(:id),UUID_TO_BIN(:quiz),UUID_TO_BIN(:revision),:version,:hash,:canonical,UUID_TO_BIN(:actor),:time)
                """, values(p).addValue("canonical", format.encode(p.rule())).addValue("actor", actor.toString())));
    }
    @Override public void pointTo(Publication p) {
        safe(() -> sql.update("""
                INSERT INTO result_release_configuration_heads(quiz_id,revision_id,configuration_id,publication_version)
                VALUES(UUID_TO_BIN(:quiz),UUID_TO_BIN(:revision),UUID_TO_BIN(:id),:version)
                ON DUPLICATE KEY UPDATE configuration_id=VALUES(configuration_id),publication_version=VALUES(publication_version)
                """, values(p)));
    }
    @Override public void record(Command c, UUID actor, String hash, Publication p, Instant recordedAt) {
        safe(() -> sql.update("""
                INSERT INTO result_release_configuration_receipts(quiz_id,revision_id,actor_id,command_id,request_hash,configuration_id,publication_version,recorded_at)
                VALUES(UUID_TO_BIN(:quiz),UUID_TO_BIN(:revision),UUID_TO_BIN(:actor),UUID_TO_BIN(:command),:request,UUID_TO_BIN(:id),:version,:recorded)
                """, values(p).addValue("actor", actor.toString()).addValue("command", c.commandId().toString())
                .addValue("request", hash).addValue("recorded", Timestamp.from(recordedAt))));
    }
    private Publication publication(ResultSet rs) throws SQLException {
        String hash = rs.getString("configuration_hash");
        return new Publication(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("quiz_id")),
                UUID.fromString(rs.getString("revision_id")), rs.getLong("publication_version"), hash,
                format.decode(rs.getString("canonical_configuration"), hash), rs.getTimestamp("published_at").toInstant(), false);
    }
    private static MapSqlParameterSource ids(UUID quiz, UUID revision) {
        return new MapSqlParameterSource("quiz", quiz.toString()).addValue("revision", revision.toString());
    }
    private static MapSqlParameterSource values(Publication p) {
        return ids(p.quizId(), p.revisionId()).addValue("id", p.id().toString()).addValue("version", p.publicationVersion())
                .addValue("hash", p.hash()).addValue("time", Timestamp.from(p.publishedAt()));
    }
    private static <T> T safe(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (ReleaseConfigurationException known) { throw known; }
        catch (RuntimeException failure) { throw new ReleaseConfigurationException(UNAVAILABLE); }
    }
}
