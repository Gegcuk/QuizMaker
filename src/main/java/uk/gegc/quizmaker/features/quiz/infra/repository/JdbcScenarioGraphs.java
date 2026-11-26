package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository.binaryIds;
import static uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository.bytes;
import static uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository.uuid;

/** Part of the revision aggregate, with no transaction or writer of its own. */
final class JdbcScenarioGraphs {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcRevisionPointReads pointReads;

    JdbcScenarioGraphs(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.pointReads = new JdbcRevisionPointReads(jdbc);
    }

    boolean lockPublication(UUID revisionId) {
        var parameters = parameters(revisionId);
        jdbc.update("""
                INSERT INTO quiz_scenario_publication_keys (quiz_revision_id) VALUES (:revision)
                ON DUPLICATE KEY UPDATE quiz_revision_id=quiz_scenario_publication_keys.quiz_revision_id
                """, parameters);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT published_revision_id IS NOT NULL FROM quiz_scenario_publication_keys
                WHERE quiz_revision_id=:revision FOR UPDATE
                """, parameters, Boolean.class));
    }

    void recordPublication(UUID revisionId) {
        jdbc.update("""
                UPDATE quiz_scenario_publication_keys SET published_revision_id=quiz_revision_id
                WHERE quiz_revision_id=:revision
                """, parameters(revisionId));
    }

    void begin(QuizRevision revision) {
        ScenarioGraph graph = revision.scenarioGraph();
        if (graph == null) return;

        jdbc.update("""
                INSERT INTO quiz_scenario_graphs (quiz_revision_id,schema_version,item_count,node_count,edge_count)
                VALUES (:revision,:version,:items,:nodes,:edges)
                """, parameters(revision.id()).addValue("version", graph.schemaVersion())
                .addValue("items", revision.items().size()).addValue("nodes", graph.nodes().size())
                .addValue("edges", graph.edges().size()));
    }

    void insertAndSeal(QuizRevision revision) {
        ScenarioGraph graph = revision.scenarioGraph();
        if (graph == null) return;

        UUID root = revision.id();
        JdbcQuizRevisionRepository.batch(jdbc, """
                INSERT INTO quiz_scenario_nodes (quiz_revision_id,id,authored_position,node_type,item_id)
                VALUES (:revision,:id,:position,:type,:item)
                """, graph.nodes().stream().map(node -> parameters(root).addValue("id", bytes(node.id()))
                .addValue("position", node.authoredPosition()).addValue("type", node.type().name())
                .addValue("item", bytes(node.itemId()))).toList());

        JdbcQuizRevisionRepository.batch(jdbc, """
                INSERT INTO quiz_scenario_edges
                    (quiz_revision_id,id,from_node_id,to_node_id,authored_position,edge_type,condition_version,condition_payload)
                VALUES (:revision,:id,:source,:target,:position,:type,:conditionVersion,:condition)
                """, graph.edges().stream().map(edge -> parameters(root).addValue("id", bytes(edge.id()))
                .addValue("source", bytes(edge.fromNodeId())).addValue("target", bytes(edge.toNodeId()))
                .addValue("position", edge.authoredPosition()).addValue("type", edge.type().name())
                .addValue("conditionVersion", edge.conditionVersion())
                .addValue("condition", PrivateSqlText.of(edge.condition()))).toList());

        jdbc.update("UPDATE quiz_scenario_graphs SET entry_node_id=:entry WHERE quiz_revision_id=:revision",
                parameters(root).addValue("entry", bytes(graph.entryNodeId())));
    }

    void compareSealedGraph(QuizRevision revision) {
        var graph = revision.scenarioGraph();
        var parameters = parameters(revision.id());
        Header header = jdbc.queryForObject("""
                SELECT quiz_revision_id,schema_version,entry_node_id,item_count,node_count,edge_count
                FROM quiz_scenario_graphs WHERE quiz_revision_id=:revision FOR SHARE
                """, parameters, (row, index) -> header(row));
        require(header != null && header.entry() != null);
        requireIdentical(header.version() == graph.schemaVersion() && header.entry().equals(graph.entryNodeId())
                && header.items() == revision.items().size()
                && header.nodes() == graph.nodes().size() && header.edges() == graph.edges().size());
        var nodes = pointReads.readIds("""
                SELECT n.* FROM
                """ + JdbcRevisionPointReads.IDS + """
                 STRAIGHT_JOIN quiz_scenario_nodes n FORCE INDEX (PRIMARY)
                  ON n.quiz_revision_id=:revision AND n.id=UUID_TO_BIN(k.id) FOR SHARE OF n
                """, graph.nodes().stream().map(ScenarioGraph.Node::id).toList(), parameters, (row, index) -> node(row));
        var edges = pointReads.readIds("""
                SELECT e.* FROM
                """ + JdbcRevisionPointReads.IDS + """
                 STRAIGHT_JOIN quiz_scenario_edges e FORCE INDEX (PRIMARY)
                  ON e.quiz_revision_id=:revision AND e.id=UUID_TO_BIN(k.id) FOR SHARE OF e
                """, graph.edges().stream().map(ScenarioGraph.Edge::id).toList(), parameters, (row, index) -> edge(row));
        requireIdentical(sameRows(nodes, graph.nodes(), ScenarioGraph.Node::id)
                && sameRows(edges, graph.edges(), ScenarioGraph.Edge::id));
    }

    Map<UUID, ScenarioGraph> read(List<UUID> roots, Map<UUID, List<QuizRevision.Item>> items, boolean current) {
        if (roots.isEmpty()) return Map.of();

        var parameters = Map.of("roots", binaryIds(roots));
        String lock = current ? " FOR SHARE" : "";
        List<Header> headers = jdbc.query("""
                SELECT quiz_revision_id,schema_version,entry_node_id,item_count,node_count,edge_count
                FROM quiz_scenario_graphs WHERE quiz_revision_id IN (:roots)
                """ + lock, parameters, (row, index) -> header(row));
        require(headers.size() == roots.size() && headers.stream().allMatch(header -> header.entry() != null));

        Map<UUID, List<ScenarioGraph.Node>> nodes = new HashMap<>();
        jdbc.query("""
                SELECT quiz_revision_id,id,authored_position,node_type,item_id
                FROM quiz_scenario_nodes WHERE quiz_revision_id IN (:roots)
                """ + lock, parameters, (org.springframework.jdbc.core.RowCallbackHandler) row -> add(nodes, row, node(row)));

        Map<UUID, List<ScenarioGraph.Edge>> edges = new HashMap<>();
        jdbc.query("""
                SELECT quiz_revision_id,id,from_node_id,to_node_id,authored_position,edge_type,condition_version,condition_payload
                FROM quiz_scenario_edges WHERE quiz_revision_id IN (:roots)
                """ + lock, parameters, (org.springframework.jdbc.core.RowCallbackHandler) row -> add(edges, row, edge(row)));

        Map<UUID, ScenarioGraph> result = new HashMap<>();
        for (Header header : headers) {
            var nodeList = nodes.getOrDefault(header.root(), List.of());
            var edgeList = edges.getOrDefault(header.root(), List.of());
            require(items.getOrDefault(header.root(), List.of()).size() == header.items()
                    && nodeList.size() == header.nodes() && edgeList.size() == header.edges());
            result.put(header.root(), new ScenarioGraph(header.version(), header.entry(), nodeList, edgeList));
        }
        return result;
    }

    private static Header header(ResultSet row) throws SQLException {
        return new Header(uuid(row, "quiz_revision_id"), row.getInt("schema_version"), uuid(row, "entry_node_id"),
                row.getInt("item_count"), row.getInt("node_count"), row.getInt("edge_count"));
    }

    private static ScenarioGraph.Node node(ResultSet row) throws SQLException {
        return new ScenarioGraph.Node(uuid(row, "id"), row.getInt("authored_position"),
                ScenarioGraph.NodeType.valueOf(row.getString("node_type")), uuid(row, "item_id"));
    }

    private static ScenarioGraph.Edge edge(ResultSet row) throws SQLException {
        return new ScenarioGraph.Edge(uuid(row, "id"), uuid(row, "from_node_id"), uuid(row, "to_node_id"),
                row.getInt("authored_position"), ScenarioGraph.EdgeType.valueOf(row.getString("edge_type")),
                row.getObject("condition_version", Integer.class), row.getString("condition_payload"));
    }

    static void requireIdentical(boolean valid) {
        if (!valid) throw new RevisionIntegrityException(RevisionIntegrityException.Reason.IDENTITY_CONFLICT);
    }

    private static <T> void add(Map<UUID, List<T>> rows, ResultSet row, T value) throws SQLException {
        rows.computeIfAbsent(uuid(row, "quiz_revision_id"), ignored -> new ArrayList<>()).add(value);
    }

    private static <T> boolean sameRows(List<T> stored, List<T> requested, Function<T, UUID> identity) {
        if (stored.size() != requested.size()) return false;
        var byId = new HashMap<UUID, T>();
        stored.forEach(row -> byId.put(identity.apply(row), row));
        return requested.stream().allMatch(row -> row.equals(byId.get(identity.apply(row))));
    }

    private static MapSqlParameterSource parameters(UUID root) {
        return new MapSqlParameterSource("revision", bytes(root));
    }

    private static void require(boolean valid) {
        if (!valid) throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
    }

    private record Header(UUID root, int version, UUID entry, int items, int nodes, int edges) { }
}
