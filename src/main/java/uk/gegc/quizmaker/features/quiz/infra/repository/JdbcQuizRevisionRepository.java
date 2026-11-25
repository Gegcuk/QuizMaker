package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.dao.DataAccessException;
import uk.gegc.quizmaker.features.learning.application.LearningPublicationItems;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession;
import uk.gegc.quizmaker.features.learning.domain.model.LearningFormException;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Repository
@Transactional
public class JdbcQuizRevisionRepository implements QuizRevisionRepository, LearningPublicationItems {
    // SQL parameter batching only; this does not limit the size of a published quiz.
    private static final int SQL_BATCH = 500;
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcRevisionSections sections;
    private final JdbcRevisionCases cases;
    private final JdbcScenarioGraphs scenarioGraphs;

    public JdbcQuizRevisionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.sections = new JdbcRevisionSections(jdbc);
        this.cases = new JdbcRevisionCases(jdbc);
        this.scenarioGraphs = new JdbcScenarioGraphs(jdbc);
    }

    @Override
    public void insert(QuizRevision revision) {
        Objects.requireNonNull(revision, "revision must not be null");
        try {
            if (revision.scenarioGraph() == null) {
                insertAggregate(revision);
            } else if (publishScenario(revision).replayed()) {
                throw new RevisionIntegrityException(RevisionIntegrityException.Reason.IDENTITY_CONFLICT);
            }
        } catch (DataAccessException failure) {
            if (revision.schemaVersion() == 3) throw SafeDataAccess.sanitize(failure);
            throw failure;
        }
    }

    @Override
    public ScenarioPublication publishScenario(QuizRevision revision) {
        if (revision == null || revision.scenarioGraph() == null)
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_CONTENT);
        try {
            if (scenarioGraphs.lockPublication(revision.id())) {
                var saved = read(revision.ownerId(), List.of(revision.id()), true).stream().findFirst();
                if (saved.isEmpty() || !saved.get().equals(revision))
                    throw new RevisionIntegrityException(RevisionIntegrityException.Reason.IDENTITY_CONFLICT);
                return new ScenarioPublication(saved.get(), true);
            }
            insertAggregate(revision);
            scenarioGraphs.recordPublication(revision.id());
            return new ScenarioPublication(revision, false);
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        }
    }

    private void insertAggregate(QuizRevision revision) {
        Map<UUID, QuestionRevision> questions = new LinkedHashMap<>();
        revision.items().forEach(item -> questions.put(item.question().id(), item.question()));
        Map<UUID, QuestionRevision> existing = readQuestions(new ArrayList<>(questions.keySet()));
        List<QuestionRevision> additions = new ArrayList<>();
        questions.forEach((id, question) -> {
            if (!existing.containsKey(id)) additions.add(question);
            else if (!existing.get(id).equals(question)) {
                throw new RevisionIntegrityException(RevisionIntegrityException.Reason.IDENTITY_CONFLICT);
            }
        });
        batch("""
                INSERT INTO question_revisions
                (id, question_id, schema_version, question_type, difficulty, question_text, content,
                 hint, explanation, attachment_url, attachment_asset_id, created_at)
                VALUES (:id, :questionId, :version, :type, :difficulty, :text, :content,
                        :hint, :explanation, :url, :assetId, :created)
                """, additions.stream().map(this::questionParameters).toList());
        List<MapSqlParameterSource> options = new ArrayList<>();
        additions.forEach(question -> question.options().forEach(option -> options.add(new MapSqlParameterSource()
                .addValue("id", bytes(option.id())).addValue("questionId", bytes(question.id()))
                .addValue("key", option.sourceKey()).addValue("position", option.authoredPosition()))));
        batch("""
                INSERT INTO question_option_revisions (id, question_revision_id, source_key, authored_position)
                VALUES (:id, :questionId, :key, :position)
                """, options);
        // Flat writes retain their v1 SQL shape; structured writes explicitly declare sealed slots.
        String sectionColumn = revision.structured() ? ", section_count" : "";
        String sectionValue = revision.structured() ? ", :sectionCount" : "";
        String caseColumn = revision.schemaVersion() == 3 ? ", case_count" : "";
        String caseValue = revision.schemaVersion() == 3 ? ", :caseCount" : "";
        jdbc.update("INSERT INTO quiz_revisions (id, quiz_id, owner_id, schema_version, title, description, created_at"
                + sectionColumn + caseColumn + ") VALUES (:id, :quizId, :ownerId, :version, :title, :description, :created"
                + sectionValue + caseValue + ")", new MapSqlParameterSource().addValue("id", bytes(revision.id()))
                .addValue("quizId", bytes(revision.quizId())).addValue("ownerId", bytes(revision.ownerId()))
                .addValue("version", revision.schemaVersion()).addValue("title", revision.title())
                .addValue("description", revision.description())
                .addValue("created", Timestamp.from(revision.createdAt())).addValue("sectionCount", revision.sections().size())
                .addValue("caseCount", revision.caseBlocks().size()));
        scenarioGraphs.begin(revision);
        batch("""
                INSERT INTO quiz_revision_items (id, quiz_revision_id, question_revision_id, authored_position)
                VALUES (:id, :quizId, :questionId, :position)
                """, revision.items().stream().map(item -> new MapSqlParameterSource()
                .addValue("id", bytes(item.id())).addValue("quizId", bytes(revision.id()))
                .addValue("questionId", bytes(item.question().id()))
                .addValue("position", item.authoredPosition())).toList());
        sections.insert(revision);
        cases.insert(revision);
        scenarioGraphs.insertAndSeal(revision);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuizRevision> findById(UUID ownerId, UUID revisionId) {
        return QuizRevisionRepository.super.findById(ownerId, revisionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuizRevision> findByIds(UUID ownerId, Collection<UUID> revisionIds) {
        return read(ownerId, revisionIds, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<QuizRevision> findByIdForShare(UUID ownerId, UUID revisionId) {
        try {
            return read(ownerId, List.of(revisionId), true).stream().findFirst();
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        }
    }

    private List<QuizRevision> read(UUID ownerId, Collection<UUID> revisionIds, boolean current) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(revisionIds, "revisionIds must not be null");
        List<UUID> ids = revisionIds.stream().map(Objects::requireNonNull).distinct().toList();
        Map<UUID, QuizRevision> result = new HashMap<>();
        String lock = current ? " FOR SHARE" : "";
        try {
            for (int start = 0; start < ids.size(); start += SQL_BATCH) {
                List<UUID> part = ids.subList(start, Math.min(start + SQL_BATCH, ids.size()));
                Map<String, Object> parameters = Map.of("owner", bytes(ownerId), "ids", binaryIds(part));
                List<Root> roots = jdbc.query("""
                        SELECT * FROM quiz_revisions WHERE owner_id = :owner AND id IN (:ids)
                        """ + lock, parameters, (rs, row) -> new Root(uuid(rs, "id"), uuid(rs, "quiz_id"),
                        uuid(rs, "owner_id"), rs.getInt("schema_version"), rs.getString("title"), rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("schema_version") == 2 || rs.getInt("schema_version") == 3 ? rs.getInt("section_count") : 0,
                        rs.getInt("schema_version") == 3 ? rs.getInt("case_count") : 0));
                if (roots.isEmpty()) continue;
                Map<String, Object> scoped = Map.of("ids", binaryIds(roots.stream().map(Root::id).toList()));
                List<ItemRow> items = jdbc.query("""
                        SELECT i.id AS item_id, i.quiz_revision_id, i.authored_position AS item_position, q.*
                        FROM quiz_revision_items i JOIN question_revisions q ON q.id = i.question_revision_id
                        WHERE i.quiz_revision_id IN (:ids) ORDER BY i.quiz_revision_id, i.authored_position
                        """ + lock, scoped, (rs, row) -> new ItemRow(uuid(rs, "quiz_revision_id"), uuid(rs, "item_id"),
                        rs.getInt("item_position"), question(rs)));
                // A locking outer SELECT does not make a nested subquery a current read.
                // Both membership and option rows must see the same newly published revision.
                Map<UUID, List<QuestionRevision.Option>> options = readOptions("""
                        EXISTS (SELECT 1 FROM quiz_revision_items i
                                WHERE i.question_revision_id = o.question_revision_id AND i.quiz_revision_id IN (:ids)
                        """ + lock + ")", scoped, current);
                Map<UUID, List<QuizRevision.Item>> byRoot = new HashMap<>();
                items.forEach(item -> byRoot.computeIfAbsent(item.rootId(), ignored -> new ArrayList<>())
                        .add(new QuizRevision.Item(item.id(), item.position(),
                                withOptions(item.question(), options.getOrDefault(item.question().id(), List.of())))));
                Map<UUID, Integer> structured = new LinkedHashMap<>();
                roots.stream().filter(root -> root.schemaVersion() == 2 || root.schemaVersion() == 3)
                        .forEach(root -> structured.put(root.id(), root.sectionCount()));
                var layouts = sections.read(structured, current);
                Map<UUID, Integer> cased = new LinkedHashMap<>();
                roots.stream().filter(root -> root.schemaVersion() == 3).forEach(root -> cased.put(root.id(), root.caseCount()));
                var blocks = cases.read(cased, current);
                var graphs = scenarioGraphs.read(roots.stream().filter(root -> root.schemaVersion() == QuizRevision.SCENARIO_FORMAT)
                        .map(Root::id).toList(), byRoot, current);
                roots.forEach(root -> {
                    var storedCases = blocks.getOrDefault(root.id(), List.of());
                    var revision = new QuizRevision(root.id(), root.quizId(), root.ownerId(),
                        root.schemaVersion(), root.title(), root.description(), root.createdAt(), byRoot.getOrDefault(root.id(), List.of()),
                        layouts.getOrDefault(root.id(), List.of()), storedCases, graphs.get(root.id()));
                    if (!revision.caseBlocks().equals(storedCases))
                        throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
                    result.put(root.id(), revision);
                });
            }
        } catch (DataAccessException failure) {
            // Sanitize before any public read exits through the repository transaction interceptor.
            throw SafeDataAccess.sanitize(failure);
        } catch (IllegalArgumentException exception) {
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
        }
        return ids.stream().filter(result::containsKey).map(result::get).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, LearningPublicationItems.Item> firstOccurrences(UUID learner, LearningSession.Source source, List<UUID> ids) {
        // Position is unique within a revision. Return one occurrence for each scheduled question.
        return learningItems(learner, source, ids, """
                i.authored_position IN (
                    SELECT MIN(authored_position) FROM quiz_revision_items
                    WHERE quiz_revision_id=:revision AND question_revision_id IN (:ids)
                    GROUP BY question_revision_id)
                """, row -> row.binding().questionRevisionId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, LearningPublicationItems.Item> savedOccurrences(UUID learner, LearningSession.Source source, List<UUID> ids) {
        return learningItems(learner, source, ids, "i.id IN (:ids)", BoundItem::id);
    }

    private Map<UUID, LearningPublicationItems.Item> learningItems(UUID learner, LearningSession.Source source, List<UUID> ids,
            String membershipPredicate, Function<BoundItem, UUID> key) {
        var result = new HashMap<UUID, LearningPublicationItems.Item>();
        try {
            for (int start = 0; start < ids.size(); start += SQL_BATCH) {
                var part = ids.subList(start, Math.min(start + SQL_BATCH, ids.size()));
                var params = Map.of("ids", binaryIds(part), "revision", bytes(source.quizRevisionId()),
                        "quiz", bytes(source.quizId()), "owner", bytes(learner), "version", source.revisionSchemaVersion());
                var selected = jdbc.query("""
                        SELECT i.id AS source_id, i.question_revision_id, b.response_contract_id,b.response_contract_version,
                               b.scoring_contract_id,b.scoring_contract_version,b.binding_hash
                        FROM quiz_revision_items i JOIN quiz_revisions r ON r.id=i.quiz_revision_id
                        JOIN question_revision_contract_bindings b ON b.question_revision_id=i.question_revision_id
                        WHERE r.id=:revision AND r.quiz_id=:quiz AND r.owner_id=:owner AND r.schema_version=:version
                        """ + " AND " + membershipPredicate, params, (rs, n) -> {
                    var binding = new QuestionContractBinding(
                        uuid(rs,"question_revision_id"), new ContractVersion(rs.getString("response_contract_id"),
                        rs.getInt("response_contract_version")), new ContractVersion(rs.getString("scoring_contract_id"),
                        rs.getInt("scoring_contract_version")));
                    if (!binding.fingerprint().equals(rs.getString("binding_hash"))) {
                        throw new LearningFormException(LearningFormException.Reason.CORRUPT);
                    }
                    return new BoundItem(uuid(rs, "source_id"), binding);
                });
                var questions = readQuestions(selected.stream().map(row -> row.binding().questionRevisionId()).distinct().toList());
                for (var row : selected) {
                    var question = questions.get(row.binding().questionRevisionId());
                    result.put(key.apply(row), new LearningPublicationItems.Item(row.id(), question, row.binding()));
                }
            }
            return result;
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        } catch (ContractResolutionException | IllegalArgumentException failure) {
            // Invalid stored identifiers, enum values or revision material are not caller-supplied input.
            throw new LearningFormException(LearningFormException.Reason.CORRUPT);
        }
    }

    private record BoundItem(UUID id, QuestionContractBinding binding) { }

    private Map<UUID, QuestionRevision> readQuestions(List<UUID> ids) {
        Map<UUID, QuestionRevision> result = new HashMap<>();
        for (int start = 0; start < ids.size(); start += SQL_BATCH) {
            Map<String, Object> parameters = Map.of("ids", binaryIds(ids.subList(start, Math.min(start + SQL_BATCH, ids.size()))));
            List<QuestionRevision> questions = jdbc.query("SELECT * FROM question_revisions WHERE id IN (:ids)",
                    parameters, (rs, row) -> question(rs));
            Map<UUID, List<QuestionRevision.Option>> options = readOptions("o.question_revision_id IN (:ids)", parameters);
            questions.forEach(question -> result.put(question.id(),
                    withOptions(question, options.getOrDefault(question.id(), List.of()))));
        }
        return result;
    }

    private Map<UUID, List<QuestionRevision.Option>> readOptions(String predicate, Map<String, ?> parameters) {
        return readOptions(predicate, parameters, false);
    }

    private Map<UUID, List<QuestionRevision.Option>> readOptions(String predicate, Map<String, ?> parameters, boolean current) {
        Map<UUID, List<QuestionRevision.Option>> result = new HashMap<>();
        jdbc.query("SELECT o.* FROM question_option_revisions o WHERE " + predicate + " ORDER BY o.authored_position"
                        + (current ? " FOR SHARE" : ""),
                parameters, (rs, row) -> {
                    result.computeIfAbsent(uuid(rs, "question_revision_id"), ignored -> new ArrayList<>())
                            .add(new QuestionRevision.Option(uuid(rs, "id"), rs.getString("source_key"),
                                    rs.getInt("authored_position")));
                    return row;
                });
        return result;
    }

    private QuestionRevision question(ResultSet rs) throws SQLException {
        try {
            return new QuestionRevision(uuid(rs, "id"), uuid(rs, "question_id"), rs.getInt("schema_version"),
                    QuestionType.valueOf(rs.getString("question_type")), Difficulty.valueOf(rs.getString("difficulty")),
                    rs.getString("question_text"), rs.getString("content"), rs.getString("hint"), rs.getString("explanation"),
                    rs.getString("attachment_url"), uuid(rs, "attachment_asset_id"), rs.getTimestamp("created_at").toInstant(), List.of());
        } catch (IllegalArgumentException exception) {
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
        }
    }

    private static QuestionRevision withOptions(QuestionRevision question, List<QuestionRevision.Option> options) {
        return new QuestionRevision(question.id(), question.questionId(), question.schemaVersion(), question.type(),
                question.difficulty(), question.questionText(), question.content(), question.hint(), question.explanation(),
                question.attachmentUrl(), question.attachmentAssetId(), question.createdAt(), options);
    }

    private MapSqlParameterSource questionParameters(QuestionRevision question) {
        return new MapSqlParameterSource().addValue("id", bytes(question.id()))
                .addValue("questionId", bytes(question.questionId())).addValue("version", question.schemaVersion())
                .addValue("type", question.type().name()).addValue("difficulty", question.difficulty().name())
                .addValue("text", PrivateSqlText.of(question.questionText()))
                .addValue("content", PrivateSqlText.of(question.content()))
                .addValue("hint", PrivateSqlText.of(question.hint()))
                .addValue("explanation", PrivateSqlText.of(question.explanation()))
                .addValue("url", PrivateSqlText.of(question.attachmentUrl())).addValue("assetId", bytes(question.attachmentAssetId()))
                .addValue("created", Timestamp.from(question.createdAt()));
    }

    private void batch(String sql, List<MapSqlParameterSource> rows) {
        batch(jdbc, sql, rows);
    }

    static void batch(NamedParameterJdbcTemplate jdbc, String sql, List<MapSqlParameterSource> rows) {
        for (int start = 0; start < rows.size(); start += SQL_BATCH) {
            jdbc.batchUpdate(sql, rows.subList(start, Math.min(start + SQL_BATCH, rows.size()))
                    .toArray(MapSqlParameterSource[]::new));
        }
    }

    static List<byte[]> binaryIds(List<UUID> ids) {
        return ids.stream().map(JdbcQuizRevisionRepository::bytes).toList();
    }

    static byte[] bytes(UUID id) {
        return id == null ? null : ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        byte[] value = rs.getBytes(column);
        if (value == null) return null;
        if (value.length != 16) throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record ItemRow(UUID rootId, UUID id, int position, QuestionRevision question) { }
    private record Root(UUID id, UUID quizId, UUID ownerId, int schemaVersion, String title, String description,
                        java.time.Instant createdAt, int sectionCount, int caseCount) { }
}
