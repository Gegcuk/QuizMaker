package uk.gegc.quizmaker.features.quiz.revision;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionStructure;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.Edge;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.EdgeType.CONDITIONAL;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.EdgeType.DEFAULT;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph.NodeType.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;

class ScenarioGraphTest {
    static final String CONDITION = "  { \"operator\": \"SCORE_IS\", \"value\": \"FULL_CREDIT\" } \n";
    static final String OPAQUE_CONDITION = "  { \"private\": \"scenario-condition-marker 🧭\" } \n";

    @Test void graphOwnsStableIdentitiesOrderAndConditionEvidence() {
        var original = scenario("ordered", 2, OPAQUE_CONDITION).scenarioGraph();
        var nodes = new ArrayList<>(original.nodes());
        var edges = new ArrayList<>(original.edges());
        Collections.reverse(nodes);
        Collections.reverse(edges);
        var reordered = new ScenarioGraph(1, original.entryNodeId(), nodes, edges);
        nodes.clear();
        edges.clear();

        assertThat(reordered).isEqualTo(original);
        assertThat(reordered.nodes()).extracting(Node::authoredPosition).containsExactly(2, 4, 9);
        assertThat(reordered.edges()).extracting(Edge::authoredPosition).containsExactly(0, 6, 0);
        assertThat(reordered.edges().get(1).condition()).isEqualTo(OPAQUE_CONDITION);
        assertThat(reordered.toString()).doesNotContain(OPAQUE_CONDITION, original.entryNodeId().toString());
        assertThat(reordered.edges().get(1).toString()).doesNotContain(OPAQUE_CONDITION);
    }

    @Test void invalidReferencesDuplicatesAndVersionFailAtTheStructuralBoundary() {
        var graph = scenario("invalid", 1, CONDITION).scenarioGraph();
        var node = graph.nodes().get(0);
        var end = graph.nodes().get(1);
        var edge = graph.edges().get(0);
        bad(() -> new ScenarioGraph(1, id("unknown-entry"), graph.nodes(), graph.edges()));
        bad(() -> new ScenarioGraph(1, graph.entryNodeId(), List.of(node, node), List.of()));
        bad(() -> new ScenarioGraph(1, graph.entryNodeId(), List.of(node, new Node(end.id(), node.authoredPosition(), TERMINAL, null)), List.of()));
        bad(() -> new ScenarioGraph(1, graph.entryNodeId(), graph.nodes(), List.of(edge, edge)));
        bad(() -> new ScenarioGraph(1, graph.entryNodeId(), graph.nodes(), List.of(edge,
                new Edge(id("different-edge"), edge.fromNodeId(), end.id(), edge.authoredPosition(), DEFAULT, null, null))));
        bad(() -> new ScenarioGraph(1, graph.entryNodeId(), graph.nodes(), List.of(
                new Edge(edge.id(), edge.fromNodeId(), id("unknown-target"), 0, DEFAULT, null, null))));
        bad(() -> new Node(node.id(), 2, ITEM, null));
        bad(() -> new Node(end.id(), 2, TERMINAL, node.itemId()));
        bad(() -> new Edge(edge.id(), node.id(), end.id(), 0, CONDITIONAL, null, CONDITION));
        bad(() -> new Edge(edge.id(), node.id(), end.id(), 0, DEFAULT, 1, CONDITION));
        bad(() -> new Edge(edge.id(), node.id(), end.id(), 0, CONDITIONAL, 1, "high-" + (char) 0xD800));
        bad(() -> new Edge(edge.id(), node.id(), end.id(), 0, CONDITIONAL, 1, "low-" + (char) 0xDC00));
        assertThatThrownBy(() -> new ScenarioGraph(9, graph.entryNodeId(), graph.nodes(), graph.edges()))
                .isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("UNSUPPORTED_VERSION");
    }

    @Test void bindsOnlySameRevisionItemsAndCannotProjectAsLinearContent() {
        var scenario = scenario("own", 2, CONDITION);
        var different = scenario("foreign", 2, CONDITION);
        bad(() -> new QuizRevision(scenario.id(), scenario.quizId(), OWNER, QuizRevision.SCENARIO_FORMAT,
                scenario.title(), null, CREATED, different.items(), List.of(), List.of(), scenario.scenarioGraph()));
        bad(() -> new QuizRevision(scenario.id(), scenario.quizId(), OWNER, 1,
                scenario.title(), null, CREATED, scenario.items(), List.of(), List.of(), scenario.scenarioGraph()));

        assertThatThrownBy(() -> RevisionStructure.from(scenario)).isInstanceOf(RevisionIntegrityException.class)
                .hasMessageContaining("UNSUPPORTED_VERSION");
        var flat = quiz("flat-structure-control", question("flat-structure-control"));
        assertThat(RevisionStructure.from(flat).provenance()).isEqualTo(RevisionStructure.Provenance.IMPLICIT_FLAT);
    }

    private static void bad(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("INVALID_CONTENT");
    }

    static QuizRevision scenario(String key, int itemCount, String condition) {
        var questions = IntStream.range(0, itemCount).mapToObj(i -> question(key + "-" + i)).toArray(QuestionRevision[]::new);
        var base = quiz(key, questions);
        var nodes = new ArrayList<Node>();
        var edges = new ArrayList<Edge>();
        var end = new Node(id("end"), 2 * itemCount + 5, TERMINAL, null);
        for (int i = 0; i < itemCount; i++) {
            var node = new Node(id("node-" + i), i * 2 + 2, ITEM, base.items().get(i).id());
            var next = i + 1 == itemCount ? end.id() : id("node-" + (i + 1));
            nodes.add(node);
            edges.add(new Edge(id("default-" + i), node.id(), next, 0, DEFAULT, null, null));
        }
        nodes.add(end);
        edges.add(new Edge(id("conditional"), nodes.get(0).id(), end.id(), 6, CONDITIONAL, 1, condition));
        var graph = new ScenarioGraph(1, nodes.get(0).id(), nodes, edges);
        return new QuizRevision(base.id(), base.quizId(), OWNER, QuizRevision.SCENARIO_FORMAT,
                base.title(), base.description(), CREATED, base.items(), List.of(), List.of(), graph);
    }
}
