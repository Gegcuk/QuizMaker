package uk.gegc.quizmaker.features.question.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.*;

@Repository
public class JdbcQuestionContractBindingRepository implements QuestionContractBindingRepository {
    private static final int SQL_BATCH = 500;
    private static final String COLUMNS = """
            b.question_revision_id, b.response_contract_id, b.response_contract_version,
            b.scoring_contract_id, b.scoring_contract_version, b.binding_hash
            """;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcQuestionContractBindingRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(readOnly = true)
    public Optional<Set<UUID>> findQuestionRevisionIds(UUID ownerId, UUID quizRevisionId) {
        scope(ownerId, quizRevisionId);
        return Optional.ofNullable(findQuestionRevisionIds(ownerId, Set.of(quizRevisionId)).get(quizRevisionId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> findQuestionRevisionIds(UUID ownerId, Set<UUID> quizRevisionIds) {
        if (ownerId == null || quizRevisionIds == null || quizRevisionIds.stream().anyMatch(Objects::isNull)) {
            throw new ContractResolutionException(REVISION_UNAVAILABLE);
        }
        var ordered = quizRevisionIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        var result = new HashMap<UUID, Set<UUID>>();
        for (int start = 0; start < ordered.size(); start += SQL_BATCH) {
            var batch = ordered.subList(start, Math.min(start + SQL_BATCH, ordered.size()));
            var parameters = new MapSqlParameterSource("owner", bytes(ownerId))
                    .addValue("quizzes", batch.stream().map(JdbcQuestionContractBindingRepository::bytes).toList());
            jdbc.query("""
                    SELECT r.id AS quiz_revision_id, i.question_revision_id AS item_question_id
                    FROM quiz_revisions r LEFT JOIN quiz_revision_items i ON i.quiz_revision_id=r.id
                    WHERE r.owner_id=:owner AND r.id IN (:quizzes)
                    """, parameters, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                var ids = result.computeIfAbsent(uuid(row.getBytes("quiz_revision_id")), ignored -> new HashSet<>());
                byte[] questionId = row.getBytes("item_question_id");
                if (questionId != null) ids.add(uuid(questionId));
            });
        }
        result.replaceAll((id, ids) -> Set.copyOf(ids));
        return Map.copyOf(result);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockQuestionRevisions(Set<UUID> questionRevisionIds) {
        var ordered = questionRevisionIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        String lock = TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "FOR SHARE" : "FOR UPDATE";
        for (int start = 0; start < ordered.size(); start += SQL_BATCH) {
            var batch = ordered.subList(start, Math.min(start + SQL_BATCH, ordered.size()));
            // Every identity exists before declaration, including questions with no binding.
            // UNION ALL executes exact primary-key lookups in canonical arm order. An IN query
            // can choose a full scan and lock unrelated identities even with FORCE INDEX.
            String points = pointReads("SELECT q.id FROM question_revisions q", "q.id", batch.size(), lock);
            var locked = jdbc.query("SELECT locked.id FROM (" + points + ") locked", pointParameters(batch),
                    (row, index) -> uuid(row.getBytes("id")));
            if (locked.size() != batch.size()) throw new ContractResolutionException(REVISION_UNAVAILABLE);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> findForRevision(UUID ownerId, UUID quizRevisionId) {
        return findQuestionRevisionIds(ownerId, quizRevisionId).map(ids -> {
            if (ids.isEmpty()) return new Snapshot(ids, Map.of());
            // Current read of binding rows only. A normal SELECT could retain a snapshot
            // from before an identical concurrent bind, even after insert accepted it.
            var ordered = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
            var bindings = new HashMap<UUID, QuestionContractBinding>();
            for (int start = 0; start < ordered.size(); start += SQL_BATCH) {
                var batch = ordered.subList(start, Math.min(start + SQL_BATCH, ordered.size()));
                String points = pointReads("SELECT " + COLUMNS + " FROM question_revision_contract_bindings b",
                        "b.question_revision_id", batch.size(), "FOR SHARE");
                jdbc.query("SELECT " + COLUMNS + " FROM (" + points + ") b", pointParameters(batch),
                        (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    var binding = binding(row);
                    bindings.put(binding.questionRevisionId(), binding);
                });
            }
            return new Snapshot(ids, bindings);
        });
    }

    private static String pointReads(String select, String primaryKey, int count, String lock) {
        return IntStream.range(0, count).mapToObj(index -> "(" + select + " WHERE " + primaryKey
                + "=:question" + index + " " + lock + ")").collect(Collectors.joining(" UNION ALL "));
    }

    private static MapSqlParameterSource pointParameters(List<UUID> ids) {
        var parameters = new MapSqlParameterSource();
        for (int index = 0; index < ids.size(); index++) parameters.addValue("question" + index, bytes(ids.get(index)));
        return parameters;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID ownerId, UUID quizRevisionId, QuestionContractBinding binding) {
        var parameters = scope(ownerId, quizRevisionId)
                .addValue("question", bytes(binding.questionRevisionId()))
                .addValue("responseId", binding.response().id()).addValue("responseVersion", binding.response().version())
                .addValue("scoringId", binding.scoring().id()).addValue("scoringVersion", binding.scoring().version())
                .addValue("hash", binding.fingerprint());
        // Revision ownership/membership is immutable. Keep this check non-locking: locking
        // joined source rows after taking the binding lock creates a lock-upgrade deadlock.
        boolean accessible = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM quiz_revision_items i JOIN quiz_revisions r ON r.id=i.quiz_revision_id
                WHERE r.owner_id=:owner AND r.id=:quiz AND i.question_revision_id=:question)
                """, parameters, Boolean.class));
        if (!accessible) throw new ContractResolutionException(REVISION_UNAVAILABLE);
        jdbc.update("""
                INSERT INTO question_revision_contract_bindings
                (question_revision_id,response_contract_id,response_contract_version,
                 scoring_contract_id,scoring_contract_version,binding_hash)
                VALUES (:question,:responseId,:responseVersion,:scoringId,:scoringVersion,:hash)
                ON DUPLICATE KEY UPDATE question_revision_id=question_revision_contract_bindings.question_revision_id
                """, parameters);
        // A duplicate only acquires the unique-key lock; none of its metadata is updated.
        // Locking read sees the committed winner even under MySQL REPEATABLE READ.
        var saved = jdbc.query("SELECT " + COLUMNS + """
                FROM question_revision_contract_bindings b
                WHERE b.question_revision_id=:question FOR UPDATE
                """, parameters, (row, index) -> binding(row));
        if (saved.isEmpty()) throw new ContractResolutionException(REVISION_UNAVAILABLE);
        if (!saved.get(0).equals(binding)) throw new ContractResolutionException(BINDING_CONFLICT);
    }

    private static QuestionContractBinding binding(ResultSet row) throws SQLException {
        try {
            var binding = new QuestionContractBinding(uuid(row.getBytes("question_revision_id")),
                    new ContractVersion(row.getString("response_contract_id"), row.getInt("response_contract_version")),
                    new ContractVersion(row.getString("scoring_contract_id"), row.getInt("scoring_contract_version")));
            if (!binding.fingerprint().equals(row.getString("binding_hash"))) {
                throw new ContractResolutionException(INVALID_STORED_BINDING);
            }
            return binding;
        } catch (ContractResolutionException | IllegalArgumentException failure) {
            throw new ContractResolutionException(INVALID_STORED_BINDING);
        }
    }

    private static MapSqlParameterSource scope(UUID ownerId, UUID quizRevisionId) {
        if (ownerId == null || quizRevisionId == null) throw new ContractResolutionException(REVISION_UNAVAILABLE);
        return new MapSqlParameterSource().addValue("owner", bytes(ownerId)).addValue("quiz", bytes(quizRevisionId));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        var buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
