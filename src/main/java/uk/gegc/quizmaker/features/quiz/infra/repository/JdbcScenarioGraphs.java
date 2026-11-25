package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlValue;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository.binaryIds;
import static uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository.bytes;
import static uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository.uuid;

/** Part of the revision aggregate, with no transaction or writer of its own. */
final class JdbcScenarioGraphs {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcScenarioGraphs(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                .addValue("condition", edge.condition() == null ? null : new PrivateCondition(edge.condition()))).toList());

        jdbc.update("UPDATE quiz_scenario_graphs SET entry_node_id=:entry WHERE quiz_revision_id=:revision",
                parameters(root).addValue("entry", bytes(graph.entryNodeId())));
    }

    Map<UUID, ScenarioGraph> read(List<UUID> roots, Map<UUID, List<QuizRevision.Item>> items, boolean current) {
        if (roots.isEmpty()) return Map.of();

        var parameters = Map.of("roots", binaryIds(roots));
        String lock = current ? " FOR SHARE" : "";
        List<Header> headers = jdbc.query("""
                SELECT quiz_revision_id,schema_version,entry_node_id,item_count,node_count,edge_count
                FROM quiz_scenario_graphs WHERE quiz_revision_id IN (:roots)
                """ + lock, parameters, (row, index) -> new Header(uuid(row, "quiz_revision_id"),
                row.getInt("schema_version"), uuid(row, "entry_node_id"), row.getInt("item_count"),
                row.getInt("node_count"), row.getInt("edge_count")));
        require(headers.size() == roots.size() && headers.stream().allMatch(header -> header.entry() != null));

        Map<UUID, List<ScenarioGraph.Node>> nodes = new HashMap<>();
        jdbc.query("""
                SELECT quiz_revision_id,id,authored_position,node_type,item_id
                FROM quiz_scenario_nodes WHERE quiz_revision_id IN (:roots)
                """ + lock, parameters, row -> {
            add(nodes, row, new ScenarioGraph.Node(uuid(row, "id"), row.getInt("authored_position"),
                    ScenarioGraph.NodeType.valueOf(row.getString("node_type")), uuid(row, "item_id")));
        });

        Map<UUID, List<ScenarioGraph.Edge>> edges = new HashMap<>();
        jdbc.query("""
                SELECT quiz_revision_id,id,from_node_id,to_node_id,authored_position,edge_type,condition_version,condition_payload
                FROM quiz_scenario_edges WHERE quiz_revision_id IN (:roots)
                """ + lock, parameters, row -> {
            add(edges, row, new ScenarioGraph.Edge(uuid(row, "id"), uuid(row, "from_node_id"), uuid(row, "to_node_id"),
                    row.getInt("authored_position"), ScenarioGraph.EdgeType.valueOf(row.getString("edge_type")),
                    row.getObject("condition_version", Integer.class), row.getString("condition_payload")));
        });

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

    private static <T> void add(Map<UUID, List<T>> rows, ResultSet row, T value) throws SQLException {
        rows.computeIfAbsent(uuid(row, "quiz_revision_id"), ignored -> new ArrayList<>()).add(value);
    }

    private static MapSqlParameterSource parameters(UUID root) {
        return new MapSqlParameterSource("revision", bytes(root));
    }

    private static void require(boolean valid) {
        if (!valid) throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_STORED_REVISION);
    }

    private record Header(UUID root, int version, UUID entry, int items, int nodes, int edges) { }

    /** JDBC TRACE logging may describe a parameter before the driver binds it. */
    private record PrivateCondition(String value) implements SqlValue {
        @Override public void setValue(PreparedStatement statement, int index) throws SQLException {
            statement.setString(index, value);
        }
        @Override public void cleanup() { }
        @Override public String toString() { return "[scenario condition]"; }
    }
}
