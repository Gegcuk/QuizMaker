package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidator;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static uk.gegc.quizmaker.features.quiz.infra.repository.JdbcScenarioGraphs.requireIdentical;

@Repository
@Transactional
public class JdbcQuizRevisionRepository implements QuizRevisionRepository, LearningPublicationItems {
    // SQL parameter batching only; this does not limit the size of a published quiz.
    static final int SQL_BATCH = 500;
    private static final String INSERT_QUESTION = """
            INSERT INTO question_revisions
            (id, question_id, schema_version, question_type, difficulty, question_text, content,
             hint, explanation, attachment_url, attachment_asset_id, created_at)
            VALUES (:id, :questionId, :version, :type, :difficulty, :text, :content,
                    :hint, :explanation, :url, :assetId, :created)
            """;
    // Leave room for JDBC escaping and large question payloads in the wire packet.
    private static final long SCENARIO_QUESTION_BATCH_BYTES = 1024L * 1024;
    private static final String SCENARIO_QUESTION_ROW = """
            JSON_ARRAY(HEX(:id%1$d), HEX(:questionId%1$d), :version%1$d, :type%1$d, :difficulty%1$d,
                :text%1$d, :content%1$d, :hint%1$d, :explanation%1$d, :url%1$d, HEX(:assetId%1$d), :created%1$d)
            """;
    private static final String INSERT_SCENARIO_ITEM = """
            INSERT INTO quiz_revision_items
                (id,quiz_revision_id,question_revision_id,authored_position,scenario_content_evidence)
            VALUES (:item,:revision,:id,:position,UNHEX(SHA2(CONCAT(
                COALESCE(:storedEvidence,scenario_question_evidence(:questionId,:version,:type,:difficulty,
                    :text,:content,:hint,:explanation,:url,:assetId,:created)),
                :options
            ),256)))
            """;
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
            var publication = scenarioGraphs.lockPublication(revision.id());
            // Only first publication creates graph authority. Exact replay preserves old snapshots.
            if (!publication.published()) ScenarioGraphValidator.validateForPublication(revision.scenarioGraph());
            byte[] fingerprint = ScenarioReplayFingerprint.of(revision);
            if (publication.published()) {
                if (publication.fingerprint() == null) {
                    adoptLegacyScenarioReplay(revision, fingerprint);
                } else {
                    requireIdentical(MessageDigest.isEqual(publication.fingerprint(), fingerprint));
                }
                return new ScenarioPublication(revision, true);
            }
            // A collision on the quiz revision itself is permanent. Other SQL failures retain
            // their persistence category, including failures while inserting question options.
            try {
                insertRoot(revision);
            } catch (DuplicateKeyException failure) {
                throw new RevisionIntegrityException(RevisionIntegrityException.Reason.IDENTITY_CONFLICT);
            }
            storeScenarioQuestions(revision);
            insertMembership(revision);
            scenarioGraphs.recordPublication(revision.id(), fingerprint);
            return new ScenarioPublication(revision, false);
        } catch (RevisionIntegrityException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        }
    }

    private void insertAggregate(QuizRevision revision) {
        var questions = questions(revision);
        var existing = readQuestions(new ArrayList<>(questions.keySet()));
        List<QuestionRevision> additions = new ArrayList<>();
        questions.forEach((id, question) -> {
            if (!existing.containsKey(id)) additions.add(question);
            else if (!existing.get(id).equals(question))
                throw new RevisionIntegrityException(RevisionIntegrityException.Reason.IDENTITY_CONFLICT);
        });
        batch(INSERT_QUESTION, additions.stream().map(this::questionParameters).toList());
        insertOptions(additions);
        insertRoot(revision);
        insertMembership(revision);
    }

    private static Map<UUID, QuestionRevision> questions(QuizRevision revision) {
        Map<UUID, QuestionRevision> questions = new LinkedHashMap<>();
        revision.items().forEach(item -> questions.put(item.question().id(), item.question()));
        return questions;
    }

    private void insertOptions(List<QuestionRevision> additions) {
        List<MapSqlParameterSource> options = new ArrayList<>();
        additions.forEach(question -> question.options().forEach(option -> options.add(new MapSqlParameterSource()
                .addValue("id", bytes(option.id())).addValue("questionId", bytes(question.id()))
                .addValue("key", PrivateSqlText.of(option.sourceKey())).addValue("position", option.authoredPosition()))));
        batch("""
                INSERT INTO question_option_revisions (id, question_revision_id, source_key, authored_position)
                VALUES (:id, :questionId, :key, :position)
                """, options);
    }

    private void insertRoot(QuizRevision revision) {
        // Flat writes retain their v1 SQL shape; structured writes explicitly declare sealed slots.
        String sectionColumn = revision.structured() ? ", section_count" : "";
        String sectionValue = revision.structured() ? ", :sectionCount" : "";
        String caseColumn = revision.schemaVersion() == 3 ? ", case_count" : "";
        String caseValue = revision.schemaVersion() == 3 ? ", :caseCount" : "";
        jdbc.update("INSERT INTO quiz_revisions (id, quiz_id, owner_id, schema_version, title, description, created_at"
                + sectionColumn + caseColumn + ") VALUES (:id, :quizId, :ownerId, :version, :title, :description, :created"
                + sectionValue + caseValue + ")", new MapSqlParameterSource().addValue("id", bytes(revision.id()))
                .addValue("quizId", bytes(revision.quizId())).addValue("ownerId", bytes(revision.ownerId()))
                .addValue("version", revision.schemaVersion()).addValue("title", PrivateSqlText.of(revision.title()))
                .addValue("description", PrivateSqlText.of(revision.description()))
                .addValue("created", Timestamp.from(revision.createdAt())).addValue("sectionCount", revision.sections().size())
                .addValue("caseCount", revision.caseBlocks().size()));
    }

    private void insertMembership(QuizRevision revision) {
        if (revision.scenarioGraph() == null) {
            batch("""
                    INSERT INTO quiz_revision_items (id,quiz_revision_id,question_revision_id,authored_position)
                    VALUES (:id,:quizId,:questionId,:position)
                    """, revision.items().stream().map(item -> new MapSqlParameterSource()
                    .addValue("id", bytes(item.id())).addValue("quizId", bytes(revision.id()))
                    .addValue("questionId", bytes(item.question().id()))
                    .addValue("position", item.authoredPosition())).toList());
        } else {
            scenarioGraphs.begin(revision);
            insertScenarioItems(revision);
        }
        sections.insert(revision);
        cases.insert(revision);
        scenarioGraphs.insertAndSeal(revision);
    }

    private void adoptLegacyScenarioReplay(QuizRevision revision, byte[] fingerprint) {
        // These graphs were sealed before replay fingerprints were introduced. Read their
        // immutable rows through MVCC while holding only this revision's publication key.
        var stored = read(revision.ownerId(), List.of(revision.id()), false);
        requireIdentical(stored.size() == 1);

        var keys = jdbc.query("""
                SELECT DISTINCT k.question_revision_id,k.option_count,k.stored_option_count
                FROM quiz_revision_items i LEFT JOIN quiz_scenario_question_keys k
                    ON k.question_revision_id=i.question_revision_id
                WHERE i.quiz_revision_id=:revision
                """, Map.of("revision", bytes(revision.id())), (row, index) ->
                new ScenarioQuestionKey(uuid(row, "question_revision_id"),
                        row.getObject("option_count", Integer.class), row.getInt("stored_option_count")));
        var questions = questions(stored.get(0));
        if (keys.size() != questions.size() || keys.stream().anyMatch(key ->
                key.id() == null || key.sealedCount() == null || key.sealedCount() != key.storedCount()
                        || key.storedCount() != questions.get(key.id()).options().size())) {
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
        }
        requireIdentical(stored.get(0).equals(revision));
        scenarioGraphs.recordPublication(revision.id(), fingerprint);
    }

    private void storeScenarioQuestions(QuizRevision revision) {
        var ordered = orderedQuestions(questions(revision).values());
        var writes = insertMissingScenarioQuestions(revision.id(), ordered);
        var additions = ordered.stream().filter(question -> writes.inserted().contains(question.id())).toList();
        insertOptions(additions);
        batch("""
                UPDATE quiz_scenario_question_keys SET evidence_ready=TRUE
                WHERE question_revision_id=:id AND NOT evidence_ready
                """, writes.pendingEvidence().stream().sorted(Comparator.comparing(UUID::toString))
                .map(id -> new MapSqlParameterSource("id", bytes(id))).toList());
    }

    private static List<QuestionRevision> orderedQuestions(Collection<QuestionRevision> questions) {
        return questions.stream().sorted(Comparator.comparing(question -> question.id().toString())).toList();
    }

    private ScenarioQuestionWrites insertMissingScenarioQuestions(UUID revisionId, List<QuestionRevision> ordered) {
        var inserted = new HashSet<UUID>();
        var pendingEvidence = new HashSet<UUID>();
        for (int start = 0; start < ordered.size(); start += SQL_BATCH) {
            var part = ordered.subList(start, Math.min(start + SQL_BATCH, ordered.size()));
            var ids = part.stream().map(QuestionRevision::id).toList();
            // The snapshot is only an insert filter. Item insertion later verifies current content.
            var visible = new HashSet<UUID>();
            jdbc.query("""
                    SELECT q.id,k.evidence_ready FROM question_revisions q
                    LEFT JOIN quiz_scenario_question_keys k ON k.question_revision_id=q.id
                    WHERE q.id IN (:ids)
                    """, Map.of("ids", binaryIds(ids)), row -> {
                var id = uuid(row, "id");
                visible.add(id);
                if (Boolean.FALSE.equals(row.getObject("evidence_ready", Boolean.class))) pendingEvidence.add(id);
            });
            var candidates = part.stream().filter(question -> !visible.contains(question.id())).toList();
            insertScenarioQuestionBatches(revisionId, candidates, inserted);
        }
        pendingEvidence.addAll(inserted);
        return new ScenarioQuestionWrites(inserted, pendingEvidence);
    }

    private void insertScenarioQuestionBatches(UUID revisionId, List<QuestionRevision> candidates, Set<UUID> inserted) {
        int start = 0;
        long bytesInBatch = 0;
        for (int index = 0; index < candidates.size(); index++) {
            var question = candidates.get(index);
            long rowBytes = 512 + 4 * Stream.of(question.questionText(), question.content(), question.hint(),
                    question.explanation(), question.attachmentUrl()).filter(Objects::nonNull).mapToLong(String::length).sum();
            if (index > start && bytesInBatch + rowBytes > SCENARIO_QUESTION_BATCH_BYTES) {
                inserted.addAll(insertScenarioQuestionBatch(revisionId, candidates.subList(start, index)));
                start = index;
                bytesInBatch = 0;
            }
            bytesInBatch += rowBytes;
        }
        if (start < candidates.size()) {
            inserted.addAll(insertScenarioQuestionBatch(revisionId, candidates.subList(start, candidates.size())));
        }
    }

    private List<UUID> insertScenarioQuestionBatch(UUID revisionId, List<QuestionRevision> candidates) {
        var statement = new StringJoiner(", ", "CALL scenario_insert_questions(:scenario, JSON_ARRAY(", "))");
        var parameters = new MapSqlParameterSource("scenario", bytes(revisionId));
        // Keep the normal bindings for timestamps and private text. MySQL packages
        // the rows and reports which inserts it actually made in this transaction.
        for (int index = 0; index < candidates.size(); index++) {
            int suffix = index;
            statement.add(SCENARIO_QUESTION_ROW.formatted(suffix));
            questionParameters(candidates.get(index)).getValues()
                    .forEach((name, value) -> parameters.addValue(name + suffix, value));
        }
        return jdbc.query(statement.toString(), parameters, (row, index) -> uuid(row, "id"));
    }

    private void insertScenarioItems(QuizRevision revision) {
        var parameters = revision.items().stream().map(item -> questionParameters(item.question())
                .addValue("item", bytes(item.id()))
                .addValue("revision", bytes(revision.id()))
                .addValue("position", item.authoredPosition())
                .addValue("options", optionEvidence(item.question().options()))
                .addValue("storedEvidence", null)).toList();
        for (int start = 0; start < parameters.size(); start += SQL_BATCH) {
            int end = Math.min(start + SQL_BATCH, parameters.size());
            try {
                batch(INSERT_SCENARIO_ITEM, parameters.subList(start, end));
            } catch (DataIntegrityViolationException failure) {
                if (!questionEvidenceMismatch(failure)) throw failure;
                retryScenarioItems(revision, start, end, parameters);
            }
        }
    }

    private void retryScenarioItems(QuizRevision revision, int start, int end, List<MapSqlParameterSource> parameters) {
        var items = revision.items().subList(start, end);
        // JDBC may have retained successful statements. This transaction's own items
        // are visible even when its snapshot predates other committed questions.
        var written = new HashSet<>(jdbc.query("""
                SELECT id FROM quiz_revision_items WHERE quiz_revision_id=:revision AND id IN (:ids)
                """, Map.of("revision", bytes(revision.id()), "ids", binaryIds(items.stream().map(QuizRevision.Item::id).toList())),
                (row, index) -> uuid(row, "id")));
        var ids = items.stream().filter(item -> !written.contains(item.id()))
                .map(item -> item.question().id()).distinct().toList();
        if (ids.isEmpty()) throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);

        var stored = new HashMap<UUID, StoredScenarioQuestion>();
        jdbc.query("""
                SELECT q.*,k.content_evidence,k.question_evidence AS stored_evidence
                FROM question_revisions q LEFT JOIN quiz_scenario_question_keys k ON k.question_revision_id=q.id
                WHERE q.id IN (:ids)
                """, Map.of("ids", binaryIds(ids)), (row, index) ->
                new StoredScenarioQuestion(question(row), row.getBytes("stored_evidence"), row.getBytes("content_evidence") != null))
                .forEach(row -> stored.put(row.question().id(), row));

        var pending = new ArrayList<MapSqlParameterSource>();
        for (int index = start; index < end; index++) {
            var item = revision.items().get(index);
            if (written.contains(item.id())) continue;
            var requested = item.question();
            var snapshot = stored.get(requested.id());
            var parameter = parameters.get(index);
            if (snapshot != null && snapshot.valid() && withOptions(snapshot.question(), requested.options()).equals(requested)) {
                // The snapshot pairs the parsed question with its recorded proof.
                // The foreign key verifies that proof and the requested options against the current row.
                parameter.addValue("storedEvidence", snapshot.evidence());
            }
            pending.add(parameter);
        }
        try {
            batch(INSERT_SCENARIO_ITEM, pending);
        } catch (DataIntegrityViolationException mismatch) {
            if (!questionEvidenceMismatch(mismatch)) throw mismatch;
            var reason = stored.values().stream().anyMatch(row -> !row.valid())
                    ? RevisionIntegrityException.Reason.INVALID_STORED_REVISION
                    : RevisionIntegrityException.Reason.IDENTITY_CONFLICT;
            throw new RevisionIntegrityException(reason);
        }
    }

    private static boolean questionEvidenceMismatch(DataIntegrityViolationException failure) {
        if (failure.getCause() instanceof SQLException sqlFailure) {
            for (var cause : sqlFailure) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1452
                        && sql.getMessage() != null && sql.getMessage().contains("fk_revision_item_scenario_question")) return true;
            }
        }
        return false;
    }

    private static byte[] optionEvidence(List<QuestionRevision.Option> options) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var evidence = digest.digest();
            var hexIds = HexFormat.of().withUpperCase();
            for (var option : options) {
                var id = hexIds.formatHex(bytes(option.id()));
                var text = id + ":" + option.authoredPosition() + ":" + option.sourceKey();
                var optionHash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
                digest.update(evidence);
                evidence = digest.digest(optionHash);
            }
            return evidence;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Scenario option evidence is unavailable", impossible);
        }
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
                            .add(option(rs));
                    return row;
                });
        return result;
    }

    private static QuestionRevision.Option option(ResultSet row) throws SQLException {
        return new QuestionRevision.Option(uuid(row, "id"), row.getString("source_key"), row.getInt("authored_position"));
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
    private record ScenarioQuestionKey(UUID id, Integer sealedCount, int storedCount) { }
    private record StoredScenarioQuestion(QuestionRevision question, byte[] evidence, boolean valid) { }
    private record ScenarioQuestionWrites(Set<UUID> inserted, Set<UUID> pendingEvidence) { }
    private record Root(UUID id, UUID quizId, UUID ownerId, int schemaVersion, String title, String description,
                        java.time.Instant createdAt, int sectionCount, int caseCount) { }
}
