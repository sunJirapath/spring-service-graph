package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code @RestController}/{@code @Controller} endpoints, including mappings declared only on
 * implemented interfaces (openapi-generator {@code interfaceOnly}). When such an interface is
 * not in the checkout (an unbuilt repo), endpoints come from the OpenAPI spec instead.
 */
public final class InboundHttpExtractor implements Extractor {

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        Value contextPath = contextPath(s);
        var missing = new java.util.LinkedHashMap<ClassOrInterfaceDeclaration, List<String>>(); // controller -> absent interfaces
        for (CompilationUnit cu : s.units()) {
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (c.isInterface() || !Ann.has(c, "RestController", "Controller")) continue;
                List<Value> classPaths = classPaths(c, s);
                for (MethodDeclaration m : Mappings.mappedMethods(c, s, md -> Mappings.mvc(md, s).isPresent())) {
                    var mapping = Mappings.mvc(m, s).orElseThrow();
                    var handler = s.evidence(override(c, m).getName()); // the method line itself, below any @Override
                    for (Value cp : classPaths) {
                        for (Value mp : mapping.paths()) {
                            Value path = Mappings.joinValues(contextPath, cp, mp);
                            for (String verb : mapping.methods()) {
                                // evidence: the mapping annotation, not the first of possibly many annotations
                                facts.add(new Fact.Endpoint(verb, path, s.evidence(mapping.annotation()), "controller", handler));
                            }
                        }
                    }
                }
                var absent = c.getImplementedTypes().stream().filter(t -> s.index().resolveType(t, c).isEmpty() && !framework(t, c))
                        .map(t -> t.getName().getIdentifier()).toList();
                if (!absent.isEmpty()) missing.put(c, absent);
            }
        }
        var exposed = new java.util.HashSet<String>();
        facts.forEach(f -> { if (f instanceof Fact.Endpoint e) exposed.add(e.method() + " " + e.path().text()); });
        missing.forEach((c, names) -> {
            // only the operations of the interfaces this controller implements, minus what controllers already expose
            var spec = OpenApiSpec.endpoints(s, contextPath, java.util.Set.copyOf(names));
            if (spec.isEmpty()) {
                facts.add(new Fact.Problem("MISSING_INTERFACE", "controller " + c.getNameAsString() + " implements "
                        + String.join(", ", names) + ", which is not in sources, and no OpenAPI spec provides its operations",
                        s.evidence(c)));
            }
            for (Fact.Endpoint e : spec) if (exposed.add(e.method() + " " + e.path().text())) facts.add(e);
        });
        return facts;
    }

    /** Spring and JDK interfaces such as {@code ErrorController} carry no mappings of the app's own. */
    private static boolean framework(com.github.javaparser.ast.type.ClassOrInterfaceType t, ClassOrInterfaceDeclaration c) {
        String name = t.getNameWithScope();
        String fqn = c.findCompilationUnit().flatMap(cu -> cu.getImports().stream()
                        .filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name)).findFirst())
                .map(i -> i.getNameAsString()).orElse(name);
        return fqn.matches("(org\\.springframework|javax?|jakarta)\\..*");
    }

    /** The controller's own method overriding {@code m} (same name and parameter types), else {@code m}. */
    private static MethodDeclaration override(ClassOrInterfaceDeclaration c, MethodDeclaration m) {
        return c.getMethodsByName(m.getNameAsString()).stream()
                .filter(o -> Mappings.signature(o).equals(Mappings.signature(m))).findFirst().orElse(m);
    }

    /** Class-level paths: on the controller, else on the first super type that declares one. */
    private static List<Value> classPaths(TypeDeclaration<?> type, Service s) {
        Optional<Mappings.Mapping> own = Mappings.mvc(type, s);
        if (own.isPresent()) return own.get().paths();
        for (var st : s.index().superTypes(type)) {
            List<Value> inherited = classPaths(st, s);
            if (!(inherited.size() == 1 && inherited.getFirst().text().isEmpty())) return inherited;
        }
        return List.of(Value.exact(""));
    }

    static Value contextPath(Service s) {
        for (String key : List.of("server.servlet.context-path", "spring.webflux.base-path", "spring.mvc.servlet.path")) {
            if (s.config().get(key) != null) return s.config().valueOf(key);
        }
        return Value.exact("");
    }
}
