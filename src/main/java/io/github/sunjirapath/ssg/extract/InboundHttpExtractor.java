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
        var missing = new ArrayList<ClassOrInterfaceDeclaration>();
        for (CompilationUnit cu : s.units()) {
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (c.isInterface() || !Ann.has(c, "RestController", "Controller")) continue;
                List<Value> classPaths = classPaths(c, s);
                for (MethodDeclaration m : Mappings.mappedMethods(c, s, md -> Mappings.mvc(md, s).isPresent())) {
                    var mapping = Mappings.mvc(m, s).orElseThrow();
                    for (Value cp : classPaths) {
                        for (Value mp : mapping.paths()) {
                            Value path = Mappings.joinValues(contextPath, cp, mp);
                            for (String verb : mapping.methods()) {
                                facts.add(new Fact.Endpoint(verb, path, s.evidence(m), "controller"));
                            }
                        }
                    }
                }
                boolean unresolvedInterface = c.getImplementedTypes().stream()
                        .anyMatch(t -> s.index().resolveType(t, c).isEmpty());
                if (unresolvedInterface) missing.add(c);
            }
        }
        if (!missing.isEmpty()) {
            List<Fact.Endpoint> spec = OpenApiSpec.endpoints(s, contextPath);
            if (spec.isEmpty()) {
                for (var c : missing) {
                    facts.add(new Fact.Problem("MISSING_INTERFACE", "controller " + c.getNameAsString()
                            + " implements an interface not found in sources and no OpenAPI spec was found",
                            s.evidence(c)));
                }
            }
            facts.addAll(spec);
        }
        return facts;
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
