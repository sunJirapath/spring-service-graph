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

/** Endpoints read from OpenAPI 3 / Swagger 2 specs in a service module (YAML or JSON). */
final class OpenApiSpec {

    private static final Set<String> VERBS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private OpenApiSpec() {}

    /**
     * Operations of the spec(s) that belong to one of {@code missingInterfaces} (simple names such as
     * {@code OrdersApi}), matched the way openapi-generator names interfaces: from the operation's
     * first tag, or from the first path segment when it was generated with {@code useTags=false}.
     */
    static List<Fact.Endpoint> endpoints(Service s, Value contextPath, java.util.Set<String> missingInterfaces) {
        var wanted = missingInterfaces.stream().map(OpenApiSpec::squash).collect(java.util.stream.Collectors.toSet());
        var precise = read(s, contextPath, names -> !java.util.Collections.disjoint(names, wanted));
        if (!precise.isEmpty()) return precise;
        // the tags do not name the missing interfaces (untagged spec, custom api names): keep every
        // operation whose generated interface is not already in sources, rather than silently none
        var inSources = s.units().stream() // this service's sources only: other modules may generate same-named APIs
                .flatMap(u -> u.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream())
                .filter(c -> c.isInterface()).map(c -> squash(c.getNameAsString())).collect(java.util.stream.Collectors.toSet());
        return read(s, contextPath, names -> java.util.Collections.disjoint(names, inSources));
    }

    /** Every operation of the spec(s), unfiltered. */
    static List<Fact.Endpoint> endpoints(Service s, Value contextPath) {
        return read(s, contextPath, null);
    }

    /** @param keep given an operation's candidate interface names (squashed), whether to keep it; null keeps all */
    private static List<Fact.Endpoint> read(Service s, Value contextPath, java.util.function.Predicate<java.util.Set<String>> keep) {
        var out = new ArrayList<Fact.Endpoint>();
        try {
            for (Path f : s.repo().files(s.moduleRoot()).stream().filter(f -> candidate(s, f)).toList()) {
                try { // one broken file must not hide the others
                    String text = Files.readString(f);
                    if (!text.contains("openapi") && !text.contains("swagger")) continue;
                    for (Node doc : Config.composeAll(new StringReader(text))) {
                        if (doc instanceof MappingNode root && (get(root, "openapi").isPresent() || get(root, "swagger").isPresent())) {
                            read(s, f, root, contextPath, keep, out);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("warn: cannot read OpenAPI spec " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("warn: cannot list " + s.moduleRoot() + ": " + e.getMessage());
        }
        return out;
    }

    private static boolean candidate(Service s, Path f) {
        String p = "/" + s.repo().relative(f);
        return (p.endsWith(".yaml") || p.endsWith(".yml") || p.endsWith(".json"))
                && !p.contains("/build/") && !p.contains("/target/") && !p.contains("/src/test/") && !p.contains("/node_modules/") && !p.contains("/.git/");
    }

    private static void read(Service s, Path file, MappingNode root, Value contextPath,
                             java.util.function.Predicate<java.util.Set<String>> keep, List<Fact.Endpoint> out) {
        Value base = baseOverride(s, root).orElseGet(() -> Value.exact(get(root, "basePath").map(OpenApiSpec::scalar)
                .or(() -> get(root, "servers").filter(SequenceNode.class::isInstance)
                        .flatMap(n -> ((SequenceNode) n).getValue().stream().findFirst())
                        .flatMap(n -> n instanceof MappingNode m ? get(m, "url") : Optional.empty())
                        .map(n -> pathOf(scalar(n))))
                .orElse("")));
        var paths = get(root, "paths");
        if (paths.isEmpty() || !(paths.get() instanceof MappingNode pm)) return;
        for (NodeTuple p : pm.getValue()) {
            if (!(p.getValueNode() instanceof MappingNode ops)) continue;
            String firstSegment = scalar(p.getKeyNode()).replaceFirst("^/+", "").split("/", 2)[0];
            for (NodeTuple op : ops.getValue()) {
                String verb = scalar(op.getKeyNode());
                if (!VERBS.contains(verb)) continue;
                String tag = op.getValueNode() instanceof MappingNode o ? get(o, "tags")
                        .filter(SequenceNode.class::isInstance).flatMap(t -> ((SequenceNode) t).getValue().stream().findFirst())
                        .map(OpenApiSpec::scalar).orElse("default") : "default";
                // a HashSet, not Set.of: the tag and the path segment often give the same name
                if (keep != null && !keep.test(new java.util.HashSet<>(List.of(squash(tag + "Api"), squash(firstSegment + "Api"))))) continue;
                String context = Mappings.join(contextPath.text());
                String server = Mappings.join(base.text());
                // An OpenAPI server URL may already contain the servlet context path.
                Value prefix = !context.equals("/") && contextPath.resolved() && base.resolved()
                        && (server.equals(context) || server.startsWith(context + "/")) ? Value.exact("") : contextPath;
                Value path = Mappings.joinValues(prefix, base, Value.exact(scalar(p.getKeyNode())))
                        .withConfidence(Confidence.RESOLVED, null);
                int line = op.getKeyNode().getStartMark().getLine() + 1;
                out.add(new Fact.Endpoint(verb.toUpperCase(), path, s.repo().evidence(file, line), "openapi", null));
            }
        }
    }

    /**
     * openapi-generator's {@code ${openapi.<name>.base-path}}, which replaces the spec's server path:
     * the key whose name matches the spec's info.title, else the only such key.
     */
    private static Optional<Value> baseOverride(Service s, MappingNode root) {
        var keys = s.config().withPrefix("openapi.").keySet().stream().filter(k -> k.matches("openapi\\..+\\.base-path")).toList();
        String title = squash(get(root, "info").map(n -> n instanceof MappingNode m ? get(m, "title").map(OpenApiSpec::scalar).orElse("") : "").orElse(""));
        return keys.stream().filter(k -> squash(k.substring("openapi.".length(), k.length() - ".base-path".length())).equals(title)).findFirst()
                .or(() -> keys.size() == 1 ? Optional.of(keys.get(0)) : Optional.empty())
                .map(s.config()::valueOf);
    }

    /** "Line Items" + "Api" and "LineItemsApi" both become "lineitemsapi". */
    static String squash(String name) {
        return name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
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
