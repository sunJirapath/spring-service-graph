package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One Spring Boot application: what extractors get to look at.
 *
 * @param units the Java sources attributed to this service
 * @param libraries the sibling modules {@code moduleRoot} depends on in its build file, transitively; no app modules
 */
public record Service(String name, Repo repo, Path moduleRoot, Config config, JavaIndex index,
                      List<CompilationUnit> units, List<Path> libraries) {

    public Path resources() {
        return moduleRoot.resolve("src/main/resources");
    }

    /** The service module followed by its {@link #libraries}. */
    public List<Path> modules() {
        var out = new ArrayList<Path>(List.of(moduleRoot));
        out.addAll(libraries);
        return out;
    }

    /** Module path relative to the repo root, "." for a single-module repo. */
    public String path() {
        String p = repo.relative(moduleRoot);
        return p.isEmpty() ? "." : p;
    }

    public Evidence evidence(Node n) {
        return index.evidence(n);
    }

    /** Folds an expression, then resolves property placeholders against this service's config. */
    public Value eval(Expression e) {
        return withConfig(index.eval(e));
    }

    public List<Value> evalAll(Expression e) {
        return index.evalAll(e).stream().map(this::withConfig).toList();
    }

    public Value withConfig(Value v) {
        if (!v.text().contains("${") && !v.text().contains("#{")) return v;
        Value r = config.resolve(v.text());
        var via = new ArrayList<>(v.via());
        via.addAll(r.via());
        return new Value(r.text(), v.confidence().and(r.confidence()), v.raw(), via);
    }
}
