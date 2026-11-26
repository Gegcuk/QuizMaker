package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
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
    private record RankedTransition(Transition transition, Integer priority) { }

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

        var selectable = collectSelectableTransitions(graph, outgoing);
        var topological = topologicalOrder(outgoing);
        int entry = indexes.get(graph.entryNodeId());
        validateDeclaredDepth(entry, outgoing, topological);
        validateSelectablePaths(graph, entry, selectable, topological);
    }

    private static List<List<Transition>> collectSelectableTransitions(ScenarioGraph graph, List<List<Transition>> outgoing) {
        List<List<Transition>> selectable = new ArrayList<>(outgoing.size());
        int conditionBytes = 0;
        for (int nodeIndex = 0; nodeIndex < graph.nodes().size(); nodeIndex++) {
            var node = graph.nodes().get(nodeIndex);
            var transitions = outgoing.get(nodeIndex);
            if (node.type() == TERMINAL) {
                if (!transitions.isEmpty()) {
                    throw new ScenarioGraphValidationException(TERMINAL_HAS_TRANSITIONS, edgePath(transitions.get(0)));
                }
                selectable.add(List.of());
                continue;
            }

            Transition fallback = null;
            Map<ScenarioConditionV1.Score, RankedTransition> winners = new EnumMap<>(ScenarioConditionV1.Score.class);
            Map<ScenarioConditionV1.Score, Set<Integer>> scorePriorities = new EnumMap<>(ScenarioConditionV1.Score.class);
            for (var transition : transitions) {
                var edge = transition.edge();
                if (edge.type() == DEFAULT) {
                    if (fallback != null) throw new ScenarioGraphValidationException(MULTIPLE_DEFAULTS, edgePath(transition));
                    fallback = transition;
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
                // A second condition for the same score has an explicit priority on both edges.
                var winner = winners.get(condition.score());
                if (winner == null || condition.priority() < winner.priority()) {
                    winners.put(condition.score(), new RankedTransition(transition, condition.priority()));
                }
            }
            if (fallback == null) throw new ScenarioGraphValidationException(MISSING_DEFAULT, nodePath(nodeIndex));

            List<Transition> possible = new ArrayList<>(ScenarioConditionV1.Score.values().length);
            for (var winner : winners.values()) possible.add(winner.transition());
            if (winners.size() < ScenarioConditionV1.Score.values().length) possible.add(fallback);
            selectable.add(possible);
        }
        return selectable;
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

    private static ArrayDeque<Integer> topologicalOrder(List<List<Transition>> outgoing) {
        var stack = new ArrayDeque<Integer>();
        var topological = new ArrayDeque<Integer>();
        var visits = new Visit[outgoing.size()];
        var nextEdge = new int[outgoing.size()];
        // The cycle rule applies to every declared edge, including edges that cannot win.
        for (int start = 0; start < outgoing.size(); start++) {
            if (visits[start] != null) continue;
            stack.push(start);
            visits[start] = Visit.ACTIVE;
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
        }
        return topological;
    }

    private static void validateDeclaredDepth(int entry, List<List<Transition>> outgoing, Iterable<Integer> topological) {
        // Reverse finishing order visits all predecessors before a shared successor.
        var longest = new int[outgoing.size()];
        longest[entry] = 1;
        for (int node : topological) {
            if (longest[node] == 0) continue;
            for (var transition : outgoing.get(node)) {
                int length = longest[node] + 1;
                if (length > MAX_PATH_NODES) throw new ScenarioGraphValidationException(PATH_LIMIT, edgePath(transition));
                int target = transition.target();
                longest[target] = Math.max(longest[target], length);
            }
        }
    }

    private static void validateSelectablePaths(ScenarioGraph graph, int entry, List<List<Transition>> selectable,
                                                Iterable<Integer> topological) {
        Map<UUID, Integer> itemIndexes = new HashMap<>();
        var precedingItems = new BitSet[selectable.size()];
        precedingItems[entry] = new BitSet();
        for (int node : topological) {
            var seen = precedingItems[node];
            if (seen == null) continue;

            UUID itemId = graph.nodes().get(node).itemId();
            if (itemId != null) {
                int item = itemIndexes.computeIfAbsent(itemId, ignored -> itemIndexes.size());
                if (seen.get(item)) throw new ScenarioGraphValidationException(REPEATED_ITEM_ON_PATH, nodePath(node) + ".itemId");
                seen.set(item);
            }

            // Each bit means at least one incoming path saw that item. Merging paths preserves
            // this fact without treating items in mutually exclusive branches as a repeat.
            for (var transition : selectable.get(node)) {
                int target = transition.target();
                if (precedingItems[target] == null) precedingItems[target] = (BitSet) seen.clone();
                else precedingItems[target].or(seen);
            }
        }
        for (int node = 0; node < precedingItems.length; node++) {
            if (precedingItems[node] == null) throw new ScenarioGraphValidationException(UNREACHABLE_NODE, nodePath(node));
        }
    }

    private static String edgePath(Transition transition) { return "edges[" + transition.index() + "]"; }

    private static String nodePath(int index) { return "nodes[" + index + "]"; }
}
