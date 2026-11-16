package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.link.Linker;
import io.github.sunjirapath.ssg.model.*;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ScannerScaleTest {
    @TempDir(factory = WorkspaceTempDirFactory.class) Path tmp;

    @Test
    void fiveThousandControllerFilesProduceEveryEndpointWithinSixtySeconds() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var repo = TempRepo.at(tmp.resolve("large-repo")).app()
                    .resource("application.properties", "spring.application.name=large\n");
            int count = 5_000;
            for (int i = 0; i < count; i++) repo.java("app/C" + i + ".java",
                    "package app; @RestController class C" + i + " { @GetMapping(\"/items/" + i + "\") void get() {} }");
            var graph = TempRepo.scan(repo);
            assertThat(graph.nodes()).filteredOn(Node.Service.class::isInstance).hasSize(1);
            assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance).extracting(Node::id)
                    .containsExactlyInAnyOrderElementsOf(IntStream.range(0, count)
                            .mapToObj(i -> Node.Endpoint.idOf("large", "GET", "/items/" + i)).toList());
            assertThat(graph.edges()).hasSize(count).allMatch(e -> e.kind() == Edge.Kind.EXPOSES);
            assertThat(graph.nodes()).extracting(Node::id).doesNotHaveDuplicates();
            assertThat(graph.findings()).isEmpty();
        });
    }

    @Test
    void fiveThousandLinkedServicesAndTenThousandEdgesRoundTripWithinSixtySeconds() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            int count = 5_000;
            var fleet = new ArrayList<Linker.ServiceFacts>();
            for (int i = 0; i < count; i++) {
                String service = "service-" + i;
                var evidence = new Evidence("repo-" + i, "src/main/java/app/Api.java", 1);
                fleet.add(new Linker.ServiceFacts(service, "repo-" + i, ".", List.of(
                        new Fact.Endpoint("GET", Value.exact("/items"), evidence, "controller", null),
                        new Fact.Call("rest-template", "GET", Value.exact("http://service-" + ((i + 1) % count) + "/items"), evidence))));
            }
            Graph graph = Linker.link(fleet, Map.of(), List.of());
            assertThat(graph.nodes()).hasSize(2 * count);
            assertThat(graph.edges()).hasSize(2 * count);
            assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS).hasSize(count)
                    .allSatisfy(e -> {
                        int caller = Integer.parseInt(e.from().substring("service:service-".length()));
                        assertThat(e.to()).isEqualTo(Node.Endpoint.idOf("service-" + ((caller + 1) % count), "GET", "/items"));
                    });
            assertThat(graph.findings()).isEmpty();
            Path out = tmp.resolve("large-snapshot");
            JsonOutput.write(graph, out);
            assertThat(JsonOutput.read(out)).isEqualTo(graph);
        });
    }
}
