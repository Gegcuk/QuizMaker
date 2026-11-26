package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionValues.require;

/** Structural evidence for one immutable revision. Executability is decided by the graph validator. */
public record ScenarioGraph(int schemaVersion, UUID entryNodeId, List<Node> nodes, List<Edge> edges) {
    public enum NodeType { ITEM, ROUTING, TERMINAL }
    public enum EdgeType { CONDITIONAL, DEFAULT }

    public record Node(UUID id, int authoredPosition, NodeType type, UUID itemId) {
        public Node {
            require(id != null && authoredPosition >= 0 && type != null);
            require((type == NodeType.ITEM) == (itemId != null));
        }
    }

    /** The condition is retained verbatim for the future versioned validator. */
    public record Edge(UUID id, UUID fromNodeId, UUID toNodeId, int authoredPosition, EdgeType type,
                       Integer conditionVersion, String condition) {
        public Edge {
            require(id != null && fromNodeId != null && toNodeId != null && authoredPosition >= 0 && type != null);
            require(type == EdgeType.CONDITIONAL
                    ? conditionVersion != null && conditionVersion > 0 && condition != null && !condition.isEmpty()
                        && condition.codePoints().noneMatch(value -> value >= Character.MIN_SURROGATE
                                && value <= Character.MAX_SURROGATE)
                    : conditionVersion == null && condition == null);
        }

        @Override public String toString() { return "ScenarioEdge[type=" + type + "]"; }
    }

    public ScenarioGraph {
        RevisionValues.version(schemaVersion);
        nodes = snapshot(nodes);
        edges = snapshot(edges);
        require(entryNodeId != null && !nodes.isEmpty());

        Map<UUID, Integer> nodePositions = new HashMap<>();
        var positions = new HashSet<Integer>();
        for (Node node : nodes) {
            require(nodePositions.putIfAbsent(node.id(), node.authoredPosition()) == null
                    && positions.add(node.authoredPosition()));
        }
        require(nodePositions.containsKey(entryNodeId));

        var edgeIds = new HashSet<UUID>();
        var edgePositions = new HashSet<EdgePosition>();
        for (Edge edge : edges) {
            require(nodePositions.containsKey(edge.fromNodeId()) && nodePositions.containsKey(edge.toNodeId())
                    && edgeIds.add(edge.id()) && edgePositions.add(new EdgePosition(edge.fromNodeId(), edge.authoredPosition())));
        }
        nodes = nodes.stream().sorted(Comparator.comparingInt(Node::authoredPosition)).toList();
        edges = edges.stream().sorted(Comparator.comparingInt((Edge edge) -> nodePositions.get(edge.fromNodeId()))
                .thenComparingInt(Edge::authoredPosition)).toList();
    }

    private static <T> List<T> snapshot(List<T> values) {
        require(values != null);
        var snapshot = new ArrayList<>(values);
        require(snapshot.stream().allMatch(Objects::nonNull));
        return snapshot;
    }

    @Override public String toString() { return "ScenarioGraph[schemaVersion=" + schemaVersion + "]"; }

    private record EdgePosition(UUID nodeId, int position) { }
}
