package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Config;
import io.github.sunjirapath.ssg.scan.Service;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Endpoints read from OpenAPI 3 / Swagger 2 specs in a service module (YAML or JSON). */
final class OpenApiSpec {

    private static final Set<String> VERBS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private OpenApiSpec() {}

    static List<Fact.Endpoint> endpoints(Service s, Value contextPath) {
        var out = new ArrayList<Fact.Endpoint>();
        try (Stream<Path> walk = Files.walk(s.moduleRoot())) {
            for (Path f : walk.filter(f -> candidate(s, f)).sorted().toList()) {
                String text = Files.readString(f);
                if (!text.contains("openapi") && !text.contains("swagger")) continue;
                for (Node doc : Config.composeAll(new StringReader(text))) {
                    if (doc instanceof MappingNode root && (get(root, "openapi").isPresent() || get(root, "swagger").isPresent())) {
                        read(s, f, root, contextPath, out);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("warn: cannot read OpenAPI specs in " + s.moduleRoot() + ": " + e.getMessage());
        }
        return out;
    }

    private static boolean candidate(Service s, Path f) {
        String p = "/" + s.repo().relative(f);
        return (p.endsWith(".yaml") || p.endsWith(".yml") || p.endsWith(".json"))
                && !p.contains("/build/") && !p.contains("/target/") && !p.contains("/src/test/") && !p.contains("/node_modules/") && !p.contains("/.git/");
    }

    private static void read(Service s, Path file, MappingNode root, Value contextPath, List<Fact.Endpoint> out) {
        // shortcut: openapi-generator lets ${openapi.<name>.base-path} override this; we use the spec's server path.
        String base = get(root, "basePath").map(OpenApiSpec::scalar)
                .or(() -> get(root, "servers").filter(SequenceNode.class::isInstance)
                        .flatMap(n -> ((SequenceNode) n).getValue().stream().findFirst())
                        .flatMap(n -> n instanceof MappingNode m ? get(m, "url") : Optional.empty())
                        .map(n -> pathOf(scalar(n))))
                .orElse("");
        var paths = get(root, "paths");
        if (paths.isEmpty() || !(paths.get() instanceof MappingNode pm)) return;
        for (NodeTuple p : pm.getValue()) {
            if (!(p.getValueNode() instanceof MappingNode ops)) continue;
            for (NodeTuple op : ops.getValue()) {
                String verb = scalar(op.getKeyNode());
                if (!VERBS.contains(verb)) continue;
                Value path = Mappings.joinValues(contextPath, Value.exact(base), Value.exact(scalar(p.getKeyNode())))
                        .withConfidence(Confidence.RESOLVED, null);
                int line = op.getKeyNode().getStartMark().getLine() + 1;
                out.add(new Fact.Endpoint(verb.toUpperCase(), path, s.repo().evidence(file, line), "openapi", null));
            }
        }
    }

    private static String pathOf(String url) {
        try {
            String p = URI.create(url.replace("{", "%7B").replace("}", "%7D")).getPath();
            return p == null ? "" : p.replace("%7B", "{").replace("%7D", "}");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static Optional<Node> get(MappingNode m, String key) {
        return m.getValue().stream().filter(t -> key.equals(scalar(t.getKeyNode()))).map(NodeTuple::getValueNode).findFirst();
    }

    private static String scalar(Node n) {
        return n instanceof ScalarNode s ? s.getValue() : "";
    }
}
