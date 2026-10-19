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
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the services in a repo: every module with a {@code @SpringBootApplication} class, or with a
 * {@code main} method that boots Spring itself ({@code SpringApplication.run}, {@code new SpringApplicationBuilder}).
 */
public final class Discovery {

    private static final List<String> EXTENSIONS = List.of("properties", "yml", "yaml");
    /** Library classes Spring instantiates in the service that depends on them. */
    private static final String[] BEANS = {"Component", "Service", "Repository", "Configuration", "AutoConfiguration",
            "Mapper", "ControllerAdvice", "RestControllerAdvice"};

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
        Modules modules = Modules.read(index.repo());
        var configs = new HashMap<Path, Config>();
        var names = new HashMap<Path, String>();
        var fallbacks = new HashMap<String, List<Path>>();
        for (Path module : apps.keySet()) {
            Config config = localConfig(index.repo(), module.resolve("src/main/resources"), profile);
            configs.put(module, config);
            Value name = config.valueOf("spring.application.name");
            if (name.resolved()) names.put(module, name.text());
            else fallbacks.computeIfAbsent(module.getFileName().toString(), k -> new ArrayList<>()).add(module);
        }
        fallbacks.forEach((folder, group) -> {
            // Same folder name, no explicit name: prefix parent folders until the names differ (transport-http).
            for (int k = 1; k <= 8; k++) {
                int depth = k;
                var uniq = new HashSet<String>();
                group.forEach(m -> uniq.add(tail(m, depth)));
                if (group.size() == 1 || uniq.size() == group.size() || k == 8) {
                    group.forEach(m -> names.put(m, tail(m, depth)));
                    return;
                }
            }
        });
        for (Path module : apps.keySet()) {
            Set<Path> libraries = modules.all(module, apps.keySet());
            List<CompilationUnit> units = apps.size() == 1 ? index.units()
                    : used(index, module, libraries, modules.direct(module), apps.keySet(), refs);
            Config config = configs.get(module);
            String serviceName = names.get(module);
            if (configRepo != null) config.putAll(remoteConfig(Repo.of(configRepo), serviceName, profile));
            services.add(new Service(serviceName, index.repo(), module, config, index, units, List.copyOf(libraries)));
        }
        return services;
    }

    /** The last {@code n} path segments joined with '-'. */
    private static String tail(Path p, int n) {
        int c = p.getNameCount();
        return p.subpath(Math.max(0, c - n), c).toString().replace('\\', '-').replace('/', '-');
    }

    private static boolean isApp(ClassOrInterfaceDeclaration c) {
        if (Ann.has(c, "SpringBootApplication")) return true;
        return c.getMethodsByName("main").stream().filter(m -> m.isStatic()).flatMap(m -> m.getBody().stream()).anyMatch(b ->
                b.findAll(ObjectCreationExpr.class).stream().anyMatch(o -> o.getType().getNameAsString().startsWith("SpringApplication"))
                || b.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals("run")
                        && call.getScope().map(Object::toString).filter(x -> x.endsWith("SpringApplication")).isPresent()));
    }

    /**
     * The service module's own sources, the beans of the library modules it depends on in its pom.xml or
     * build.gradle (transitively; {@link #BEANS} classes, plus controllers from direct dependencies only), and
     * the library sources (in no app's module) those use: the files declaring a type they reference, directly or
     * through other library files. So a shared module's Feign clients and DB access count for every service
     * that pulls them in, while its controllers count only where the launcher module depends on it, not in every
     * service that reaches it through some starter. A type the service implements or extends is left out,
     * because a local implementation of a {@code @FeignClient} interface is the server, not a client.
     */
    // shortcut: every bean class in a dependency counts, whether or not component scanning or an
    // auto-configuration import would load it; read spring.factories/AutoConfiguration.imports if that over-counts.
    private static List<CompilationUnit> used(JavaIndex index, Path module, Set<Path> libraries, Set<Path> direct,
                                              Set<Path> appModules, Map<CompilationUnit, List<TypeDeclaration<?>>> refs) {
        var own = new ArrayList<CompilationUnit>();
        var roots = new ArrayList<CompilationUnit>();
        for (CompilationUnit cu : index.units()) {
            Path m = index.moduleOf(index.fileOf(cu));
            if (m.equals(module)) own.add(cu);
            else if (libraries.contains(m) && cu.findAll(TypeDeclaration.class).stream().anyMatch(t ->
                    Ann.has(t, BEANS) || direct.contains(m) && Ann.has(t, "RestController", "Controller"))) roots.add(cu);
        }
        var implemented = new HashSet<TypeDeclaration<?>>();
        var supers = new ArrayDeque<TypeDeclaration<?>>();
        own.forEach(cu -> supers.addAll(cu.findAll(TypeDeclaration.class).stream().<TypeDeclaration<?>>map(t -> t).toList()));
        while (!supers.isEmpty()) {
            for (TypeDeclaration<?> st : index.superTypes(supers.pop())) if (implemented.add(st)) supers.push(st);
        }
        var units = new LinkedHashSet<>(own);
        units.addAll(roots);
        var queue = new ArrayDeque<>(units);
        var mine = new HashSet<>(libraries);
        mine.add(module);
        while (!queue.isEmpty()) {
            for (TypeDeclaration<?> ref : refs.computeIfAbsent(queue.pop(), cu -> referencedTypes(index, cu))) {
                TypeDeclaration<?> t = index.preferring(ref, mine); // a local-api interface, not its @FeignClient twin
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
