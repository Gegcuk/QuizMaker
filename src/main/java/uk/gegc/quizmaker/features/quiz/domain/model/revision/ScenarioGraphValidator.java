package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.EdgeType.DEFAULT;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.NodeType.ITEM;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.NodeType.TERMINAL;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidationException.Reason.*;

/** Executable graph v1 validation. Stored graph decoding and exact replay use structural checks. */
public final class ScenarioGraphValidator {
    private static final int MAX_NODES = 1_024;
    private static final int MAX_EDGES = 4_096;
    private static final int MAX_PATH_NODES = 768;
    private static final int MAX_CONDITION_BYTES = 256;
    private static final int MAX_TOTAL_CONDITION_BYTES = 256 * 1_024;

    private enum Visit { ACTIVE, COMPLETE }

    private record Transition(int index, int target, ScenarioGraph.Edge edge) { }

    private ScenarioGraphValidator() { }

    public static void validateForPublication(ScenarioGraph graph) {
        int size = graph.nodes().size();
        if (size > MAX_NODES) throw new ScenarioGraphValidationException(NODE_LIMIT, "nodes");
        if (graph.edges().size() > MAX_EDGES) throw new ScenarioGraphValidationException(EDGE_LIMIT, "edges");

        Map<UUID, Integer> indexes = new HashMap<>();
        List<List<Transition>> outgoing = new ArrayList<>(size);
        for (int node = 0; node < size; node++) {
            indexes.put(graph.nodes().get(node).id(), node);
            outgoing.add(new ArrayList<>());
        }
        for (int edge = 0; edge < graph.edges().size(); edge++) {
            var value = graph.edges().get(edge);
            outgoing.get(indexes.get(value.fromNodeId())).add(new Transition(edge, indexes.get(value.toNodeId()), value));
        }

        validateTransitions(graph, outgoing);
        validatePaths(indexes.get(graph.entryNodeId()), outgoing);
    }

    private static void validateTransitions(ScenarioGraph graph, List<List<Transition>> outgoing) {
        int conditionBytes = 0;
        for (int nodeIndex = 0; nodeIndex < graph.nodes().size(); nodeIndex++) {
            var node = graph.nodes().get(nodeIndex);
            var transitions = outgoing.get(nodeIndex);
            if (node.type() == TERMINAL) {
                if (!transitions.isEmpty()) {
                    throw new ScenarioGraphValidationException(TERMINAL_HAS_TRANSITIONS, edgePath(transitions.get(0)));
                }
                continue;
            }

            boolean hasDefault = false;
            Map<ScenarioConditionV1.Score, Set<Integer>> scorePriorities = new EnumMap<>(ScenarioConditionV1.Score.class);
            for (var transition : transitions) {
                var edge = transition.edge();
                if (edge.type() == DEFAULT) {
                    if (hasDefault) throw new ScenarioGraphValidationException(MULTIPLE_DEFAULTS, edgePath(transition));
                    hasDefault = true;
                    continue;
                }

                String path = edgePath(transition) + ".condition";
                if (node.type() != ITEM) throw new ScenarioGraphValidationException(INVALID_CONDITION_SOURCE, path);
                if (edge.conditionVersion() != 1) {
                    throw new ScenarioGraphValidationException(UNSUPPORTED_CONDITION_VERSION,
                            edgePath(transition) + ".conditionVersion");
                }
                conditionBytes += conditionSize(edge.condition(), path);
                if (conditionBytes > MAX_TOTAL_CONDITION_BYTES) {
                    throw new ScenarioGraphValidationException(TOTAL_CONDITION_SIZE_LIMIT, path);
                }
                var condition = ScenarioConditionV1.parse(edge.condition(), path);
                validatePriority(scorePriorities, condition, path);
            }
            if (!hasDefault) throw new ScenarioGraphValidationException(MISSING_DEFAULT, nodePath(nodeIndex));
        }
    }

    private static int conditionSize(String value, String path) {
        // For well-formed Unicode, the UTF-8 byte count is at least the UTF-16 code unit count.
        if (value.length() > MAX_CONDITION_BYTES) throw new ScenarioGraphValidationException(CONDITION_SIZE_LIMIT, path);
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CONDITION_BYTES) throw new ScenarioGraphValidationException(CONDITION_SIZE_LIMIT, path);
        return bytes;
    }

    private static void validatePriority(Map<ScenarioConditionV1.Score, Set<Integer>> scorePriorities,
                                         ScenarioConditionV1 condition, String path) {
        // In v1 two predicates can both match exactly when they test the same score outcome.
        Set<Integer> priorities = scorePriorities.computeIfAbsent(condition.score(), ignored -> new HashSet<>());
        Integer priority = condition.priority();
        if ((!priorities.isEmpty() && (priority == null || priorities.contains(null))) || !priorities.add(priority)) {
            throw new ScenarioGraphValidationException(AMBIGUOUS_CONDITIONS, path + ".priority");
        }
    }

    private static void validatePaths(int entry, List<List<Transition>> outgoing) {
        var stack = new ArrayDeque<Integer>();
        var topological = new ArrayDeque<Integer>();
        var visits = new Visit[outgoing.size()];
        var nextEdge = new int[outgoing.size()];
        stack.push(entry);
        visits[entry] = Visit.ACTIVE;
        while (!stack.isEmpty()) {
            int node = stack.peek();
            var edges = outgoing.get(node);
            if (nextEdge[node] == edges.size()) {
                visits[node] = Visit.COMPLETE;
                topological.addFirst(stack.pop());
                continue;
            }
            var transition = edges.get(nextEdge[node]++);
            int target = transition.target();
            if (visits[target] == Visit.ACTIVE) throw new ScenarioGraphValidationException(CYCLE, edgePath(transition));
            if (visits[target] == null) {
                visits[target] = Visit.ACTIVE;
                stack.push(target);
            }
        }

        for (int node = 0; node < visits.length; node++) {
            if (visits[node] == null) throw new ScenarioGraphValidationException(UNREACHABLE_NODE, nodePath(node));
        }

        // Reachability, acyclicity and required defaults make every sink a terminal node.
        // Reverse finishing order visits all predecessors before a shared successor.
        var longest = new int[outgoing.size()];
        longest[entry] = 1;
        for (int node : topological) {
            for (var transition : outgoing.get(node)) {
                int length = longest[node] + 1;
                if (length > MAX_PATH_NODES) throw new ScenarioGraphValidationException(PATH_LIMIT, edgePath(transition));
                int target = transition.target();
                longest[target] = Math.max(longest[target], length);
            }
        }
    }

    private static String edgePath(Transition transition) { return "edges[" + transition.index() + "]"; }

    private static String nodePath(int index) { return "nodes[" + index + "]"; }
}
