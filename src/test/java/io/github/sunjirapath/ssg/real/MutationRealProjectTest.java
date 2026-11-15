package io.github.sunjirapath.ssg.real;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.diff.GraphDiff;
import io.github.sunjirapath.ssg.link.PathMatch;
import io.github.sunjirapath.ssg.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

class MutationRealProjectTest {
    @TempDir(factory = io.github.sunjirapath.ssg.WorkspaceTempDirFactory.class) Path tmp;
    private static final Pattern GET = Pattern.compile("@GetMapping\\s*\\(\\s*(?:(?:value|path)\\s*=\\s*)?\"([^\"]*)\"");
    private record Mapping(Node.Endpoint endpoint, Path file, int line, int start, int end) {}

    @ParameterizedTest(name = "rename and new call {0}")
    @MethodSource("io.github.sunjirapath.ssg.real.RealProjects#supportedProjects")
    void endpointRenameAndMissingRestTemplateCallHaveOnlyExpectedContractChanges(RealProjects.Project project) throws Exception {
        Path copy = RealProjects.copy(project, tmp), process = tmp.resolve("process");
        Graph initial = RealProjects.scan(copy, tmp.resolve("initial"), process);
        Mapping mapping = mapping(initial, copy, false);
        var endpoint = mapping.endpoint();
        String oldUrl = "http://" + endpoint.service() + endpoint.path();
        String missing = "/ssg-security-no-such-endpoint-7c382a";
        assertThat(initial.nodes()).noneMatch(n -> n instanceof Node.Endpoint ep && ep.path().equals(missing));

        // A distinct caller makes an otherwise unused single-service endpoint a breaking contract.
        // Both checkouts are disposable; no build files or sources in REAL_PROJECTS_DIR are changed.
        var caller = TempRepo.at(tmp.resolve("ssg-security-caller")).app()
                .resource("application.properties", "spring.application.name=ssg-security-caller\n")
                .java("probe/Calls.java", calls(oldUrl, null));
        Path baseOut = tmp.resolve("base"), headOut = tmp.resolve("head");
        Graph base = RealProjects.scan(copy, baseOut, process, caller.root());
        assertThat(base.edges()).anyMatch(e -> e.kind() == Edge.Kind.CALLS && e.to().equals(endpoint.id())
                && e.from().equals(Node.Service.idOf("ssg-security-caller")));

        var lines = Files.readAllLines(mapping.file());
        String line = lines.get(mapping.line());
        lines.set(mapping.line(), line.substring(0, mapping.start()) + "/ssg-security-renamed-7c382a" + line.substring(mapping.end()));
        Files.writeString(mapping.file(), String.join("\n", lines) + "\n");
        caller.java("probe/Calls.java", calls(oldUrl, "http://" + endpoint.service() + missing));
        Graph head = RealProjects.scan(copy, headOut, process, caller.root());

        Set<String> after = new HashSet<>(head.nodes().stream().map(Node::id).toList());
        assertThat(base.nodes().stream().filter(Node.Endpoint.class::isInstance).map(Node::id).filter(id -> !after.contains(id)).toList())
                .containsExactly(endpoint.id());
        Set<String> before = new HashSet<>(base.nodes().stream().map(Node::id).toList());
        var added = head.nodes().stream().filter(Node.Endpoint.class::isInstance).map(Node.Endpoint.class::cast)
                .filter(ep -> !before.contains(ep.id())).toList();
        assertThat(added).singleElement().satisfies(ep -> assertThat(ep.path()).endsWith("/ssg-security-renamed-7c382a"));
        Set<String> known = new HashSet<>(base.findings().stream().map(Object::toString).toList());
        var drift = head.findings().stream().filter(f -> f.kind() == Finding.Kind.CONTRACT_DRIFT && !known.contains(f.toString())).toList();
        assertThat(drift).anyMatch(f -> f.message().contains(missing));
        assertThat(drift).allMatch(f -> f.message().contains(missing) || f.message().contains(endpoint.path()));
        var diff = GraphDiff.compare(base, head);
        boolean ownerIncomplete = head.findings().stream().anyMatch(f -> f.service().equals(endpoint.service())
                && (f.kind() == Finding.Kind.MISSING_INTERFACE || f.kind() == Finding.Kind.PARSE_ERROR
                || f.kind() == Finding.Kind.INCOMPLETE_ANALYSIS));
        if (!ownerIncomplete) {
            var headNodes = head.nodes().stream().collect(java.util.stream.Collectors.toMap(Node::id, n -> n));
            var callerEdges = head.edges().stream().filter(e -> e.kind() == Edge.Kind.CALLS
                    && e.from().equals(Node.Service.idOf("ssg-security-caller"))).map(e -> {
                Node target = headNodes.get(e.to());
                String owner = target == null ? "n/a" : switch (target) {
                    case Node.Endpoint ep -> ep.service();
                    case Node.Service service -> service.name();
                    case Node.Table table -> table.service();
                    default -> "n/a";
                };
                return "to=" + e.to() + ", attrs=" + e.attrs() + ", targetType="
                        + (target == null ? "missing" : target.getClass().getSimpleName()) + ", targetOwner=" + owner;
            }).toList();
            String oldUrlPath = oldUrl.substring(("http://" + endpoint.service()).length());
            var matchingEndpoints = head.nodes().stream().filter(Node.Endpoint.class::isInstance)
                    .map(Node.Endpoint.class::cast).filter(ep -> PathMatch.matches(oldUrlPath, ep.path()))
                    .map(ep -> ep.id() + " path=" + ep.path() + " service=" + ep.service()).toList();
            assertThat(diff.breaking()).as("breaking=%s; caller CALLS=%s; removed endpoint id=%s path=%s service=%s; "
                    + "head endpoints matching old URL path=%s", diff.breaking(), callerEdges, endpoint.id(),
                    endpoint.path(), endpoint.service(), matchingEndpoints)
                    .anyMatch(s -> s.startsWith("Removed ") && s.contains(endpoint.path()));
        } else {
            // NOTE: GraphDiff suppresses a removed endpoint when its owner has incomplete analysis, since it cannot prove the endpoint is still called.
            assertThat(diff.breaking()).as("owner findings: %s", head.findings().stream()
                    .filter(f -> f.service().equals(endpoint.service()))
                    .map(f -> f.kind() + ": " + f.message()).toList())
                    .noneMatch(s -> s.startsWith("Removed ") && s.contains(endpoint.path()));
        }
        assertThat(diff.breaking()).allMatch(s -> s.contains(endpoint.path()) || s.contains(missing));
        // CONTRACT_DRIFT is itself breaking under the public diff contract; no unrelated breaks are allowed.
        var gate = RealProjects.cli(process, "", "diff", "--base", baseOut.toString(), "--head", headOut.toString(), "--fail-on-breaking");
        assertThat(gate.code()).as("incomplete lines: %s", diff.incomplete())
                .isEqualTo(diff.incomplete().isEmpty() ? 3 : 4);
    }

    @ParameterizedTest(name = "fleet linking {0}")
    @MethodSource("io.github.sunjirapath.ssg.real.RealProjects#fleets")
    void fleetLinkedEndpointRemovalExitsThree(RealProjects.Project project) throws Exception {
        Path copy = RealProjects.copy(project, tmp), baseOut = tmp.resolve("base"), headOut = tmp.resolve("head"), process = tmp.resolve("process");
        Graph base = RealProjects.scan(copy, baseOut, process);
        Mapping mapping = mapping(base, copy, true);
        var lines = Files.readAllLines(mapping.file());
        lines.set(mapping.line(), lines.get(mapping.line()).replace("@GetMapping", "@SsgRemovedMapping"));
        Files.writeString(mapping.file(), String.join("\n", lines) + "\n");
        Graph head = RealProjects.scan(copy, headOut, process);
        assertThat(head.nodes()).noneMatch(n -> n.id().equals(mapping.endpoint().id()));
        var diff = GraphDiff.compare(base, head);
        var endpoint = mapping.endpoint();
        var baseCalls = base.edges().stream().filter(e -> e.kind() == Edge.Kind.CALLS && e.to().equals(endpoint.id())).toList();
        var baseCallers = baseCalls.stream().map(Edge::from).collect(java.util.stream.Collectors.toSet());
        var baseCallDetails = baseCalls.stream().map(e -> "from=" + e.from() + ", to=" + e.to() + ", confidence=" + e.confidence()
                + ", attrs=" + e.attrs() + ", evidence=" + e.evidence().stream().map(v -> v.file() + ":" + v.line()).toList()).toList();
        var headNodes = head.nodes().stream().collect(java.util.stream.Collectors.toMap(Node::id, n -> n));
        var ownerFindings = head.findings().stream().filter(f -> f.service().equals(endpoint.service()))
                .map(f -> f.kind() + ": " + f.message()).toList();
        var headCallDetails = head.edges().stream().filter(e -> e.kind() == Edge.Kind.CALLS && baseCallers.contains(e.from()))
                .map(e -> "from=" + e.from() + ", to=" + e.to() + ", confidence=" + e.confidence() + ", attrs=" + e.attrs()
                        + ", targetType=" + (headNodes.get(e.to()) == null ? "missing" : headNodes.get(e.to()).getClass().getSimpleName())).toList();
        var contractDrift = head.findings().stream().filter(f -> f.kind() == Finding.Kind.CONTRACT_DRIFT && f.message().contains(endpoint.path()))
                .map(Finding::message).toList();
        assertThat(diff.breaking()).as("endpoint id=%s path=%s service=%s; breaking=%s; incomplete=%s; owner findings=%s\nbase CALLS=%s\nhead CALLS from base callers=%s; CONTRACT_DRIFT=%s",
                endpoint.id(), endpoint.path(), endpoint.service(), diff.breaking(), diff.incomplete(), ownerFindings, baseCallDetails, headCallDetails, contractDrift)
                .anyMatch(s -> s.contains(mapping.endpoint().path()));
        var result = RealProjects.cli(process, "", "diff", "--base", baseOut.toString(), "--head", headOut.toString(), "--fail-on-breaking");
        // Main returns 4 for incomplete analysis before it returns 3 for breaking changes.
        assertThat(result.code()).as("incomplete lines: %s", diff.incomplete())
                .isEqualTo(diff.incomplete().isEmpty() ? 3 : 4);
    }

    @Test
    void bareRestTemplateCreationIsOutsideCallRecognition() throws Exception {
        Path process = tmp.resolve("limitation-process");
        var caller = TempRepo.at(tmp.resolve("ssg-security-bare-resttemplate")).app()
                .resource("application.properties", "spring.application.name=ssg-security-bare-resttemplate\n")
                .java("probe/Calls.java", "package probe; import org.springframework.stereotype.Component; "
                        + "import org.springframework.web.client.RestTemplate; @Component class Calls { "
                        + "void existing() { new RestTemplate().getForObject(\"http://example.test/\", String.class); } }");
        Graph graph = RealProjects.scan(caller.root(), tmp.resolve("limitation-out"), process);
        // LIMITATION: an inline ObjectCreationExpr has no declared receiver name for RestTemplate detection.
        assertThat(graph.edges()).noneMatch(e -> e.kind() == Edge.Kind.CALLS);
    }

    // A bare new RestTemplate().getForObject(..) remains intentionally out of scope because its receiver is not type-resolved.
    private static String calls(String existing, String missing) throws Exception {
        var json = new ObjectMapper();
        return "package probe; import org.springframework.stereotype.Component; "
                + "import org.springframework.web.client.RestTemplate; @Component class Calls { "
                + "private final RestTemplate restTemplate = new RestTemplate(); "
                + "void existing() { restTemplate.getForObject(" + json.writeValueAsString(existing) + ", String.class); }"
                + (missing == null ? "" : " void missing() { restTemplate.getForObject(" + json.writeValueAsString(missing) + ", String.class); }") + " }";
    }

    private static Mapping mapping(Graph graph, Path copy, boolean requireLinkedCaller) throws Exception {
        Map<String, Node> nodes = new HashMap<>();
        graph.nodes().forEach(n -> nodes.put(n.id(), n));
        for (Edge edge : graph.edges()) {
            if (edge.kind() != Edge.Kind.EXPOSES || !(nodes.get(edge.to()) instanceof Node.Endpoint ep) || !ep.method().equals("GET")) continue;
            boolean linkedCaller = !requireLinkedCaller || graph.edges().stream().anyMatch(e -> {
                String method = e.attrs().get("method"), path = e.attrs().get("path");
                return e.kind() == Edge.Kind.CALLS && e.to().equals(ep.id())
                        && nodes.get(e.from()) instanceof Node.Service s && !s.name().equals(ep.service())
                        && method != null && !method.isBlank() && !method.equals("*")
                        && path != null && !path.isBlank() && !path.equals("/**") && !path.endsWith("/**");
            });
            for (Evidence e : edge.evidence()) if (e.file().endsWith(".java")) {
                Path file = copy.resolve(e.file()).normalize();
                assertThat(file.startsWith(copy)).isTrue();
                String line = Files.readAllLines(file).get(e.line() - 1);
                var match = GET.matcher(line);
                if (match.find() && !match.group(1).contains("${")
                        && (requireLinkedCaller || match.group(1).startsWith("/"))
                        && ep.path().indexOf('{') < 0 && ep.path().indexOf('}') < 0 && ep.path().indexOf('*') < 0) {
                    String literal = match.group(1);
                    String endpointLastSegment = ep.path().substring(ep.path().lastIndexOf('/') + 1);
                    String literalLastSegment = literal.substring(literal.lastIndexOf('/') + 1);
                    if (!endpointLastSegment.equals(literalLastSegment)) continue;
                    if (linkedCaller) {
                        int start = requireLinkedCaller ? match.start(1) + literal.lastIndexOf(literalLastSegment) : match.start(1);
                        int end = requireLinkedCaller ? start + literalLastSegment.length() : match.end(1);
                        return new Mapping(ep, file, e.line() - 1, start, end);
                    }
                }
            }
        }
        if (requireLinkedCaller) throw new AssertionError(copy.getFileName() + " has no fully literal GET mapping with a concrete external caller");
        Assumptions.assumeTrue(false, copy.getFileName() + " has no fully literal GET mapping");
        return null;
    }
}
