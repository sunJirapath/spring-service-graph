package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Graph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class GraphViewIndexTest {

    @Test
    void indexedLookupsKeepGraphOrderAndFilterByKind() {
        var first = edge(Edge.Kind.CALLS, "a", "b");
        var otherKind = edge(Edge.Kind.USES, "a", "b");
        var last = edge(Edge.Kind.CALLS, "a", "c");
        var incoming = edge(Edge.Kind.CALLS, "c", "b");
        var view = new GraphView(new Graph(List.of(), List.of(first, otherKind, last, incoming), List.of()));

        assertThat(view.from("a", Edge.Kind.CALLS)).containsExactly(first, last);
        assertThat(view.to("b", Edge.Kind.CALLS)).containsExactly(first, incoming);
        assertThat(view.from("missing", Edge.Kind.CALLS)).isEmpty();
    }

    private static Edge edge(Edge.Kind kind, String from, String to) {
        return new Edge(kind, from, to, Confidence.EXACT, new TreeMap<>(), List.of());
    }
}
