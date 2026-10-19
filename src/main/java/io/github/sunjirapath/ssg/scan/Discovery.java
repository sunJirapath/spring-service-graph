package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import io.github.sunjirapath.ssg.model.Value;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds the services in a repo: every module with a {@code @SpringBootApplication} class, or with a
 * {@code main} method that boots Spring itself ({@code SpringApplication.run}, {@code new SpringApplicationBuilder}).
 */
public final class Discovery {

    private static final List<String> EXTENSIONS = List.of("properties", "yml", "yaml");

    private Discovery() {}

    /**
     * @param configRepo optional Spring Cloud Config repo checkout; its files override local config
     */
    public static List<Service> discover(JavaIndex index, String profile, Path configRepo) {
        var apps = new TreeMap<Path, CompilationUnit>();
        for (CompilationUnit cu : index.units()) {
            if (index.isGenerated(cu)) continue;
            boolean app = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .anyMatch(Discovery::isApp);
            if (app) apps.putIfAbsent(index.moduleOf(index.fileOf(cu)), cu);
        }
        var services = new ArrayList<Service>();
        var refs = new HashMap<CompilationUnit, List<TypeDeclaration<?>>>();
        for (Path module : apps.keySet()) {
            List<CompilationUnit> units = apps.size() == 1 ? index.units()
                    : used(index, index.units().stream().filter(u -> index.moduleOf(index.fileOf(u)).equals(module)).toList(),
                            apps.keySet(), refs);
            Config config = localConfig(index.repo(), module.resolve("src/main/resources"), profile);
            Value name = config.valueOf("spring.application.name");
            String serviceName = name.resolved() ? name.text() : module.getFileName().toString();
            if (configRepo != null) config.putAll(remoteConfig(Repo.of(configRepo), serviceName, profile));
            services.add(new Service(serviceName, index.repo(), module, config, index, units));
        }
        return services;
    }

    private static boolean isApp(ClassOrInterfaceDeclaration c) {
        if (Ann.has(c, "SpringBootApplication")) return true;
        return c.getMethodsByName("main").stream().filter(m -> m.isStatic()).flatMap(m -> m.getBody().stream()).anyMatch(b ->
                b.findAll(ObjectCreationExpr.class).stream().anyMatch(o -> o.getType().getNameAsString().startsWith("SpringApplication"))
                || b.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals("run")
                        && call.getScope().map(Object::toString).filter(x -> x.endsWith("SpringApplication")).isPresent()));
    }

    /**
     * {@code own} plus the library sources (in no app's module) it uses: the files declaring a type it
     * references, directly or through other library files. A type the service implements or extends is
     * left out, because a local implementation of a {@code @FeignClient} interface is the server, not a client.
     */
    // shortcut: usage only, so library beans the service never names (auto-configurations) stay unattributed;
    // reading Maven/Gradle module dependencies would catch them.
    private static List<CompilationUnit> used(JavaIndex index, List<CompilationUnit> own, java.util.Set<Path> appModules,
                                              Map<CompilationUnit, List<TypeDeclaration<?>>> refs) {
        var implemented = new HashSet<TypeDeclaration<?>>();
        var supers = new ArrayDeque<TypeDeclaration<?>>();
        own.forEach(cu -> supers.addAll(cu.findAll(TypeDeclaration.class).stream().<TypeDeclaration<?>>map(t -> t).toList()));
        while (!supers.isEmpty()) {
            for (TypeDeclaration<?> st : index.superTypes(supers.pop())) if (implemented.add(st)) supers.push(st);
        }
        var units = new LinkedHashSet<>(own);
        var queue = new ArrayDeque<>(own);
        while (!queue.isEmpty()) {
            for (TypeDeclaration<?> t : refs.computeIfAbsent(queue.pop(), cu -> referencedTypes(index, cu))) {
                if (implemented.contains(t)) continue;
                CompilationUnit cu = t.findCompilationUnit().orElseThrow();
                if (!appModules.contains(index.moduleOf(index.fileOf(cu))) && units.add(cu)) queue.push(cu);
            }
        }
        return List.copyOf(units);
    }

    /** Types named in {@code cu} as field, parameter, variable, generic or class-literal types, or as static call/field scopes; not in extends/implements. */
    private static List<TypeDeclaration<?>> referencedTypes(JavaIndex index, CompilationUnit cu) {
        var names = new LinkedHashMap<String, Node>();
        for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
            if (!(t.getParentNode().orElse(null) instanceof TypeDeclaration<?>)) names.putIfAbsent(t.getNameWithScope(), t);
        }
        for (NameExpr n : cu.findAll(NameExpr.class)) {
            if (Character.isUpperCase(n.getNameAsString().charAt(0))) names.putIfAbsent(n.getNameAsString(), n);
        }
        var out = new ArrayList<TypeDeclaration<?>>();
        names.forEach((name, at) -> index.resolveType(name, at).ifPresent(out::add));
        return out;
    }

    private static Config localConfig(Repo repo, Path resources, String profile) {
        var files = new ArrayList<Path>();
        for (String base : List.of("bootstrap", "application")) {
            for (String ext : EXTENSIONS) files.add(resources.resolve(base + "." + ext));
            for (String ext : EXTENSIONS) files.add(resources.resolve(base + "-" + profile + "." + ext));
        }
        return Config.load(repo, files, profile);
    }

    // shortcut: only the config repo root is read, not per-app subfolders or search-locations.
    private static Config remoteConfig(Repo repo, String app, String profile) {
        var files = new ArrayList<Path>();
        for (String base : List.of("application", app)) {
            for (String ext : EXTENSIONS) files.add(repo.root().resolve(base + "." + ext));
            for (String ext : EXTENSIONS) files.add(repo.root().resolve(base + "-" + profile + "." + ext));
        }
        return Config.load(repo, files, profile);
    }
}
