package uk.gegc.quizmaker.features.quiz.revision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.Edge;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.Node;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidationException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidationException.Reason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidator.Evidence.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidator.compile;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.EdgeType.CONDITIONAL;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.EdgeType.DEFAULT;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.NodeType.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidationException.Reason.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidator.validateForPublication;

class ScenarioGraphValidatorTest {
    private static final String FULL = "{\"operator\":\"SCORE_IS\",\"value\":\"FULL_CREDIT\"}";
    private static final String NOT_FULL = FULL.replace("FULL_CREDIT", "NOT_FULL_CREDIT");
    private static final Node QUESTION = node(0, ITEM);
    private static final Node EXPLANATION = node(1, ROUTING);
    private static final Node END = node(2, TERMINAL);

    @Test void takesAnExplicitDefaultAndAcceptsDisjointOutcomesWithoutPriorities() {
        validateForPublication(graph(List.of(QUESTION, EXPLANATION, END), List.of(
                fallback(QUESTION, END, 9), condition(QUESTION, END, 4, FULL),
                condition(QUESTION, EXPLANATION, 7, NOT_FULL), fallback(EXPLANATION, END, 0))));
        validateForPublication(graph(List.of(END), List.of()));
    }

    @Test void aMissingAcceptedAnswerCannotSelectEvenTheDefaultAtAScoreFork() {
        var defaultEdge = fallback(QUESTION, END, 0);
        var winningEdge = condition(QUESTION, EXPLANATION, 2, priority(1));
        var onward = fallback(EXPLANATION, END, 0);
        var routes = compile(graph(List.of(QUESTION, EXPLANATION, END), List.of(defaultEdge,
                condition(QUESTION, END, 1, priority(20)), winningEdge, onward)));

        assertThat(routes.select(QUESTION.id(), NO_ACCEPTED_ANSWER)).isEmpty();
        assertThat(routes.select(QUESTION.id(), FULL_CREDIT)).contains(winningEdge);
        assertThat(routes.select(QUESTION.id(), NOT_FULL_CREDIT)).contains(defaultEdge);
        assertThat(routes.select(EXPLANATION.id(), NO_ACCEPTED_ANSWER)).contains(onward);
        assertThatThrownBy(() -> routes.select(END.id(), FULL_CREDIT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void overlappingConditionsRequireDistinctExplicitPrioritiesRegardlessOfAuthoredOrder() {
        var nodes = new ArrayList<>(List.of(QUESTION, EXPLANATION, END));
        var valid = new ArrayList<>(List.of(fallback(QUESTION, END, 0), fallback(EXPLANATION, END, 0),
                condition(QUESTION, END, 2, priority(15)), condition(QUESTION, EXPLANATION, 9, priority(1))));
        var tie = new ArrayList<>(valid);
        tie.set(3, condition(QUESTION, EXPLANATION, 9, priority(15)));
        var random = new Random(573);
        for (int permutation = 0; permutation < 25; permutation++) {
            Collections.shuffle(nodes, random);
            Collections.shuffle(valid, random);
            Collections.shuffle(tie, random);
            validateForPublication(new ScenarioGraph(1, QUESTION.id(), nodes, valid));
            rejects(new ScenarioGraph(1, QUESTION.id(), nodes, tie), AMBIGUOUS_CONDITIONS, "edges[2].condition.priority");
        }
        for (var pair : List.of(List.of(FULL, FULL), List.of(priority(1), FULL), List.of(FULL, priority(1)))) {
            rejects(graph(List.of(QUESTION, END), List.of(fallback(QUESTION, END, 0),
                    condition(QUESTION, END, 1, pair.get(0)), condition(QUESTION, END, 2, pair.get(1)))),
                    AMBIGUOUS_CONDITIONS, "edges[2].condition.priority");
        }
    }

    @Test void rejectsSelfLoopsAndLongerCyclesAsWellAsDisconnectedNodes() {
        rejects(graph(List.of(QUESTION), List.of(fallback(QUESTION, QUESTION, 0))), CYCLE, "edges[0]");
        rejects(graph(List.of(QUESTION, EXPLANATION, END), List.of(fallback(QUESTION, EXPLANATION, 0),
                condition(QUESTION, END, 1, FULL), fallback(EXPLANATION, QUESTION, 0))), CYCLE, "edges[2]");
        rejects(graph(List.of(QUESTION, EXPLANATION, END), List.of(fallback(QUESTION, END, 0),
                fallback(EXPLANATION, END, 0))), UNREACHABLE_NODE, "nodes[1]");
    }

    @Test void losingPriorityDoesNotMakeItsTargetReachableButAnotherOutcomeCan() {
        var nodes = List.of(QUESTION, EXPLANATION, END);
        var edges = new ArrayList<>(List.of(fallback(QUESTION, END, 0),
                condition(QUESTION, EXPLANATION, 1, priority(2)), condition(QUESTION, END, 2, priority(1)),
                fallback(EXPLANATION, END, 0)));
        rejects(graph(nodes, edges), UNREACHABLE_NODE, "nodes[1]");

        edges.set(0, fallback(QUESTION, EXPLANATION, 0));
        validateForPublication(graph(nodes, edges));
    }

    @Test void fallbackDoesNotMakeItsTargetReachableWhenBothScoresHaveMatchingConditions() {
        var nodes = List.of(QUESTION, EXPLANATION, END);
        var edges = List.of(fallback(QUESTION, EXPLANATION, 0), condition(QUESTION, END, 1, FULL),
                condition(QUESTION, END, 2, NOT_FULL), fallback(EXPLANATION, END, 0));
        rejects(graph(nodes, edges), UNREACHABLE_NODE, "nodes[1]");
    }

    @Test void cyclesAreRejectedEvenInLosingTransitionsOrDisconnectedComponents() {
        rejects(graph(List.of(QUESTION, END), List.of(fallback(QUESTION, END, 0),
                condition(QUESTION, END, 1, priority(1)), condition(QUESTION, QUESTION, 2, priority(2)))),
                CYCLE, "edges[2]");
        rejects(graph(List.of(QUESTION, EXPLANATION, END), List.of(fallback(QUESTION, END, 0),
                fallback(EXPLANATION, EXPLANATION, 0))), CYCLE, "edges[1]");
    }

    @Test void rejectsRepeatedSourceItemEvenWhenOnlyOnePathToTheSecondOccurrenceContainsIt() {
        var first = node(1, ITEM);
        var second = new Node(new UUID(0, 3), 3, ITEM, first.itemId());
        var bypass = node(2, ROUTING);
        var finish = node(4, TERMINAL);
        var nodes = List.of(QUESTION, first, bypass, second, finish);
        var edges = List.of(fallback(QUESTION, bypass, 0), condition(QUESTION, first, 1, FULL),
                fallback(first, second, 0), fallback(bypass, second, 0), fallback(second, finish, 0));
        rejects(graph(nodes, edges), REPEATED_ITEM_ON_PATH, "nodes[3].itemId");
    }

    @Test void allowsSharedSourceItemInExclusiveBranchesEvenWithALosingEdgeBetweenThem() {
        var left = node(1, ITEM);
        var right = new Node(new UUID(0, 2), 2, ITEM, left.itemId());
        var finish = node(3, TERMINAL);
        validateForPublication(graph(List.of(QUESTION, left, right, finish), List.of(
                fallback(QUESTION, left, 0), condition(QUESTION, right, 1, FULL),
                fallback(left, finish, 0), condition(left, finish, 1, priority(1)),
                condition(left, right, 2, priority(2)), fallback(right, finish, 0))));
    }

    @Test void rejectsDeadEndsAndChecksDefaultAndTerminalRules() {
        rejects(graph(List.of(QUESTION, EXPLANATION), List.of(fallback(QUESTION, EXPLANATION, 0))),
                MISSING_DEFAULT, "nodes[1]");
        rejects(graph(List.of(QUESTION, END), List.of(condition(QUESTION, END, 0, FULL))),
                MISSING_DEFAULT, "nodes[0]");
        rejects(graph(List.of(QUESTION, END), List.of(fallback(QUESTION, END, 0), fallback(QUESTION, END, 1))),
                MULTIPLE_DEFAULTS, "edges[1]");
        rejects(graph(List.of(QUESTION, END), List.of(fallback(QUESTION, END, 0), fallback(END, QUESTION, 0))),
                TERMINAL_HAS_TRANSITIONS, "edges[1]");
    }

    @Test void rejectsUnknownOperatorVersionAndConditionsWithoutASourceItem() {
        rejects(singleCondition(FULL.replace("SCORE_IS", "EVAL")), UNSUPPORTED_OPERATOR, "edges[1].condition.operator");
        var unknownVersion = new Edge(edgeId(QUESTION, 1), QUESTION.id(), END.id(), 1, CONDITIONAL, 2, FULL);
        rejects(graph(List.of(QUESTION, END), List.of(fallback(QUESTION, END, 0), unknownVersion)),
                UNSUPPORTED_CONDITION_VERSION, "edges[1].conditionVersion");
        rejects(graph(List.of(EXPLANATION, END), List.of(fallback(EXPLANATION, END, 0),
                condition(EXPLANATION, END, 1, FULL))), INVALID_CONDITION_SOURCE, "edges[1].condition");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " ", "[]", "null", "{\"operator\":\"SCORE_IS\"}",
            "{\"operator\":\"SCORE_IS\",\"value\":\"FULL_CREDIT\",\"itemId\":\"other-private-item\"}",
            "{\"operator\":\"SCORE_IS\",\"value\":\"FULL_CREDIT\",\"value\":\"private-duplicate\"}",
            "{\"operator\":\"SCORE_IS\",\"value\":true}",
            "{\"operator\":\"SCORE_IS\",\"value\":\"FULL_CREDIT\"} {\"private-trailing\":0}",
            "{\"operator\":\"SCORE_IS\",\"value\":\"FULL_CREDIT\",\"priority\":{\"private-priority\":1}}"
    })
    void malformedOrUnknownConditionFieldsAreRefusedWithoutExposingInput(String payload) {
        rejects(singleCondition(payload), INVALID_CONDITION,
                payload.contains("{\"private-priority\":1}") ? "edges[1].condition.priority" : "edges[1].condition");
    }

    @Test void scoreAndPriorityAreStrictlyTyped() {
        rejects(singleCondition(FULL.replace("FULL_CREDIT", "PARTIAL_CREDIT")), INVALID_CONDITION, "edges[1].condition.value");
        for (String value : List.of("-1", "1.0", "null", "2147483648", "\"1\"")) {
            rejects(singleCondition(FULL.substring(0, FULL.length() - 1) + ",\"priority\":" + value + "}"),
                    INVALID_CONDITION, "edges[1].condition.priority");
        }
        validateForPublication(singleCondition(priority(0)));
        validateForPublication(singleCondition(priority(Integer.MAX_VALUE)));
    }

    @Test void conditionSizeUsesUtf8BytesAndSeparatelyCapsTheAggregate() {
        validateForPublication(singleCondition(padded(FULL, 256)));
        rejects(singleCondition(padded(FULL, 257)), CONDITION_SIZE_LIMIT, "edges[1].condition");
        rejects(singleCondition(padded(FULL, 255) + "é"), CONDITION_SIZE_LIMIT, "edges[1].condition");

        var edges = new ArrayList<Edge>();
        edges.add(fallback(QUESTION, END, 0));
        for (int index = 1; index <= 1_025; index++) {
            edges.add(condition(QUESTION, END, index, padded(priority(index), index < 1_024 ? 256 : 128)));
        }
        validateForPublication(graph(List.of(QUESTION, END), edges));
        edges.set(1_025, condition(QUESTION, END, 1_025, padded(priority(1_025), 129)));
        rejects(graph(List.of(QUESTION, END), edges), TOTAL_CONDITION_SIZE_LIMIT, "edges[1025].condition");
    }

    @Test void boundsNodesAndEdgesIndependently() {
        var nodes = new ArrayList<>(IntStream.range(0, 1_024)
                .mapToObj(index -> node(index, index < 512 ? ITEM : TERMINAL)).toList());
        var edges = new ArrayList<Edge>();
        for (int index = 0; index < 512; index++) {
            Node source = nodes.get(index);
            int left = 2 * index + 1;
            edges.add(fallback(source, nodes.get(left), 0));
            if (left + 1 < nodes.size()) edges.add(condition(source, nodes.get(left + 1), 1, priority(0)));
        }
        // The binary tree makes every node selectable independently of the losing edges.
        for (int position = 2; edges.size() < 4_096; position++) {
            edges.add(condition(QUESTION, nodes.get(2), position, priority(position)));
        }
        validateForPublication(graph(nodes, edges));
        nodes.add(node(1_024, TERMINAL));
        rejects(graph(nodes, edges), NODE_LIMIT, "nodes");
        nodes.remove(1_024);
        edges.add(condition(QUESTION, nodes.get(2), 4_096, priority(4_096)));
        rejects(graph(nodes, edges), EDGE_LIMIT, "edges");
    }

    @Test void longestPathCountsSharedSuccessorEvenAfterAFasterPathReachesItFirst() {
        validateForPublication(pathWithShortcut(768));
        rejects(pathWithShortcut(769), PATH_LIMIT, "edges[768]");
    }

    private static ScenarioGraph pathWithShortcut(int size) {
        var nodes = new ArrayList<Node>();
        nodes.add(QUESTION);
        IntStream.range(1, size - 1).mapToObj(index -> node(index, ROUTING)).forEach(nodes::add);
        var end = node(size - 1, TERMINAL);
        nodes.add(end);
        var edges = new ArrayList<Edge>();
        edges.add(fallback(QUESTION, end, 0));
        edges.add(condition(QUESTION, nodes.get(1), 1, NOT_FULL));
        for (int index = 1; index < size - 1; index++) edges.add(fallback(nodes.get(index), nodes.get(index + 1), 0));
        return graph(nodes, edges);
    }

    private static ScenarioGraph singleCondition(String payload) {
        return graph(List.of(QUESTION, END), List.of(fallback(QUESTION, END, 0), condition(QUESTION, END, 1, payload)));
    }

    private static ScenarioGraph graph(List<Node> nodes, List<Edge> edges) {
        return new ScenarioGraph(1, nodes.get(0).id(), nodes, edges);
    }

    private static Node node(int position, ScenarioGraph.NodeType type) {
        return new Node(new UUID(0, position), position, type, type == ITEM ? new UUID(1, position) : null);
    }

    private static Edge fallback(Node from, Node to, int position) {
        return new Edge(edgeId(from, position), from.id(), to.id(), position, DEFAULT, null, null);
    }

    private static Edge condition(Node from, Node to, int position, String payload) {
        return new Edge(edgeId(from, position), from.id(), to.id(), position, CONDITIONAL, 1, payload);
    }

    private static UUID edgeId(Node from, int position) { return new UUID(from.authoredPosition(), position); }

    private static String priority(int value) { return FULL.substring(0, FULL.length() - 1) + ",\"priority\":" + value + "}"; }

    private static String padded(String value, int size) { return value + " ".repeat(size - value.length()); }

    private static void rejects(ScenarioGraph graph, Reason reason, String path) {
        assertThatThrownBy(() -> validateForPublication(graph)).isInstanceOfSatisfying(ScenarioGraphValidationException.class,
                failure -> {
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure.path()).isEqualTo(path);
                    assertThat(failure.getMessage()).isEqualTo("Scenario graph rejected: " + reason + " at " + path);
                    assertThat(failure.getCause()).isNull();
                });
    }
}
