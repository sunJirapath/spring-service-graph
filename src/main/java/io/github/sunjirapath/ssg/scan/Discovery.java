package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import io.github.sunjirapath.ssg.model.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the services in a repo: every module with a {@code @SpringBootApplication} class, or with a
 * {@code main} method that boots Spring itself ({@code SpringApplication.run}, {@code new SpringApplicationBuilder}).
 */
public final class Discovery {

    private static final List<String> EXTENSIONS = List.of("properties", "yml", "yaml");
    /** Library classes Spring instantiates in the service that depends on them. */
    private static final String[] BEANS = {"Component", "Service", "Repository", "Configuration", "AutoConfiguration",
            "Mapper", "Controller", "RestController", "ControllerAdvice", "RestControllerAdvice"};
    private static final Set<String> JPA_REPOSITORIES = Set.of("Repository", "CrudRepository", "ListCrudRepository",
            "PagingAndSortingRepository", "ListPagingAndSortingRepository", "JpaRepository");
    private static final Set<String> MONGO_REPOSITORIES = Set.of("MongoRepository");
    private static final Set<String> REACTIVE_MONGO_REPOSITORIES = Set.of("ReactiveMongoRepository");
    private static final Pattern FACTORIES =
            Pattern.compile("(?m)^\\s*org\\.springframework\\.boot\\.autoconfigure\\.EnableAutoConfiguration\\s*[=:](.*)$");
    private static final String IMPORTS = "org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private Discovery() {}

    /**
     * @param configRepo optional Spring Cloud Config repo checkout; its files override local config
     */
    public static List<Service> discover(JavaIndex index, String profile, Path configRepo) {
        var apps = new TreeMap<Path, CompilationUnit>();
        for (CompilationUnit cu : index.units()) {
            if (index.isGenerated(cu)) continue;
            boolean app = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .anyMatch(c -> isApp(index, c));
            if (app) apps.putIfAbsent(index.moduleOf(index.fileOf(cu)), cu);
        }
        var services = new ArrayList<Service>();
        var refs = new HashMap<CompilationUnit, List<TypeDeclaration<?>>>();
        Modules modules = Modules.read(index.repo());
        var configs = new HashMap<Path, Config>();
        var names = new HashMap<Path, String>();
        var fallbacks = new HashMap<String, List<Path>>();
        Path serverModule = null;
        for (Path module : apps.keySet()) {
            Config config = localConfig(index.repo(), module.resolve("src/main/resources"), profile);
            configs.put(module, config);
            if (serverModule == null && apps.get(module).findAll(ClassOrInterfaceDeclaration.class).stream()
                    .anyMatch(c -> Ann.has(c, "EnableConfigServer"))) serverModule = module;
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
        // The config server's own config (if it is in this repo) says which folders of the config repo hold the files.
        Config server = serverModule == null ? null : configs.get(serverModule);
        List<String> gitPaths = server == null ? List.of() : locations(server, "git.searchpaths", false);
        List<String> nativePaths = server == null ? List.of() : locations(server, "native.searchlocations", true);
        Path remoteRoot = configRepo;
        Repo remoteRepo = configRepo == null ? null : Repo.of(configRepo);
        if (configRepo == null && !nativePaths.isEmpty()) { // a classpath: location of the scanned server is its own config repo
            remoteRoot = serverModule.resolve("src/main/resources");
            remoteRepo = index.repo();
            gitPaths = List.of();
        }
        var remotePaths = new ArrayList<>(gitPaths);
        remotePaths.addAll(nativePaths);
        List<NacosSeed.Row> nacos = NacosSeed.read(index.repo());
        for (Path module : apps.keySet()) {
            Set<Path> libraries = modules.all(module, apps.keySet());
            Config config = configs.get(module);
            String serviceName = names.get(module);
            if (!nacos.isEmpty()) config.putAll(NacosSeed.load(index.repo(), nacos, serviceName, profile));
            if (remoteRoot != null) config.putAll(remoteConfig(remoteRepo, remoteRoot, remotePaths, serviceName, profile));
            // shortcut: without build metadata, one app still receives all sources; add descriptors to scope sibling modules.
            List<CompilationUnit> units = apps.size() == 1 && !modules.hasBuildMetadata() ? index.units()
                    : used(index, module, libraries, apps.keySet(), refs, config);
            services.add(new Service(serviceName, index.repo(), module, config, index, units, List.copyOf(libraries)));
        }
        return services;
    }

    /** The last {@code n} path segments joined with '-'. */
    private static String tail(Path p, int n) {
        int c = p.getNameCount();
        return p.subpath(Math.max(0, c - n), c).toString().replace('\\', '-').replace('/', '-');
    }

    static boolean isApp(JavaIndex index, ClassOrInterfaceDeclaration c) {
        if (hasAnnotation(index, c, "SpringBootApplication")) return true;
        return c.getMethodsByName("main").stream().filter(m -> m.isStatic()).flatMap(m -> m.getBody().stream()).anyMatch(b ->
                b.findAll(ObjectCreationExpr.class).stream().anyMatch(o -> o.getType().getNameAsString().startsWith("SpringApplication"))
                || b.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals("run")
                        && call.getScope().map(Object::toString).filter(x -> x.endsWith("SpringApplication")).isPresent()));
    }

    /** Recognize annotations directly or through source-local runtime meta-annotations. */
    private static boolean hasAnnotation(JavaIndex index, TypeDeclaration<?> c, String... names) {
        var pending = new ArrayDeque<AnnotationExpr>(c.getAnnotations());
        var seen = new HashSet<TypeDeclaration<?>>();
        while (!pending.isEmpty()) {
            AnnotationExpr annotation = pending.pop();
            if (Set.of(names).contains(annotation.getName().getIdentifier())) return true;
            index.resolveType(annotation.getNameAsString(), annotation)
                    .filter(declaration -> declaration instanceof AnnotationDeclaration && seen.add(declaration))
                    .filter(Discovery::runtimeRetention)
                    .ifPresent(declaration -> ((AnnotationDeclaration) declaration).getAnnotations().forEach(pending::add));
        }
        return false;
    }

    private static boolean runtimeRetention(TypeDeclaration<?> declaration) {
        return Ann.find(declaration, "Retention").flatMap(a -> Ann.attr(a, "value"))
                .map(value -> value.toString().equals("RUNTIME") || value.toString().endsWith(".RUNTIME"))
                .orElse(false);
    }

    /**
     * The service module's own sources, the beans of the library modules it depends on in its pom.xml or
     * build.gradle (transitively; {@link #BEANS} classes), and
     * the library sources (in no app's module) those use: the files declaring a type they reference, directly or
     * through other library files. So a shared module's Feign clients and DB access count for every service
     * that pulls them in. A local implementation of a {@code @FeignClient} interface is the server, not a client,
     * so that implemented interface is left out.
     * A library bean counts only if Spring would load it: see {@link #loaded}.
     */
    private static List<CompilationUnit> used(JavaIndex index, Path module, Set<Path> libraries,
                                              Set<Path> appModules, Map<CompilationUnit, List<TypeDeclaration<?>>> refs,
                                              Config config) {
        var own = new ArrayList<CompilationUnit>();
        var library = new ArrayList<CompilationUnit>();
        var beans = new HashSet<CompilationUnit>();
        for (CompilationUnit cu : index.units()) {
            Path m = index.moduleOf(index.fileOf(cu));
            if (m.equals(module)) own.add(cu);
            else if (libraries.contains(m)) {
                library.add(cu);
                if (cu.findAll(TypeDeclaration.class).stream().anyMatch(t -> Ann.has(t, BEANS))) beans.add(cu);
            }
        }
        List<CompilationUnit> roots = loaded(index, config, module, own, library, beans, libraries);
        if (roots == null) roots = library.stream().filter(beans::contains).toList();
        var implemented = new HashSet<TypeDeclaration<?>>();
        var supers = new ArrayDeque<TypeDeclaration<?>>();
        own.forEach(cu -> supers.addAll(cu.findAll(TypeDeclaration.class).stream().<TypeDeclaration<?>>map(t -> t).toList()));
        while (!supers.isEmpty()) {
            for (TypeDeclaration<?> st : index.superTypes(supers.pop())) if (implemented.add(st)) supers.push(st);
        }
        var units = new LinkedHashSet<>(own);
        units.addAll(roots.stream().filter(cu -> types(cu).stream()
                .noneMatch(t -> Ann.has(t, "FeignClient") && implemented.contains(t))).toList());
        var queue = new ArrayDeque<>(units);
        var mine = new HashSet<>(libraries);
        mine.add(module);
        while (!queue.isEmpty()) {
            for (TypeDeclaration<?> ref : refs.computeIfAbsent(queue.pop(), cu -> referencedTypes(index, cu))) {
                TypeDeclaration<?> t = index.preferring(ref, mine); // a local-api interface, not its @FeignClient twin
                if (implemented.contains(t) && Ann.has(t, "FeignClient")) continue;
                CompilationUnit cu = t.findCompilationUnit().orElseThrow();
                if (!appModules.contains(index.moduleOf(index.fileOf(cu))) && units.add(cu)) queue.push(cu);
            }
        }
        return List.copyOf(units);
    }

    /**
     * The library files Spring would load for the service: beans in a component-scanned package (the
     * {@code @SpringBootApplication} package or its {@code scanBasePackages}, {@code @ComponentScan}), mapper interfaces
     * registered by {@code @MapperScan}, JPA/Mongo repositories and JPA entities, the auto-configurations in {@code META-INF}, and
     * {@code @Import}ed classes and local XML beans, each also through meta-annotations and through the classes it loads in turn.
     * Null when a scan attribute cannot be read, or nothing names a package: the caller then counts every bean.
     */
    // shortcut: component excludeFilters, non-regex repository exclusions, exclude=, @Conditional* and ImportSelector results are ignored, and
    // non-literal packages or unresolved class anchors fall back to every bean; read them if a service over-counts.
    private static List<CompilationUnit> loaded(JavaIndex index, Config config, Path module, List<CompilationUnit> own,
                                                List<CompilationUnit> library, Set<CompilationUnit> beans, Set<Path> libraries) {
        var resourceRoots = new ArrayList<Path>();
        resourceRoots.add(module.resolve("src/main/resources"));
        libraries.stream().sorted().forEach(lib -> resourceRoots.add(lib.resolve("src/main/resources")));
        resourceRoots.removeIf(root -> !inside(index.repo().root(), root));
        var xmlBeans = new XmlBeans(config, resourceRoots);
        var packages = new ArrayList<Pattern>();
        var feignPackages = new ArrayList<Pattern>();
        var mapperScans = new ArrayList<MapperScan>();
        var entityPackages = new ArrayList<Pattern>();
        var jpaRepositories = new ArrayList<RepositoryScan>();
        var mongoRepositories = new ArrayList<RepositoryScan>();
        var reactiveMongoRepositories = new ArrayList<RepositoryScan>();
        var defaultPersistencePackages = new ArrayList<Pattern>();
        var named = new HashSet<String>();
        libraries.forEach(lib -> named.addAll(autoConfigs(lib)));
        var pending = new ArrayDeque<TypeDeclaration<?>>();
        own.forEach(cu -> types(cu).stream().filter(t -> t instanceof ClassOrInterfaceDeclaration c && isApp(index, c))
                .forEach(pending::add));
        var candidates = new ArrayList<>(own);
        candidates.addAll(library);
        // A plain main naming itself adds no scan information; keep the existing no-scan fallback.
        bootSources(index, own, candidates).stream().filter(t -> !pending.contains(t))
                .forEach(t -> t.getFullyQualifiedName().ifPresent(named::add));
        var roots = new HashSet<CompilationUnit>();
        var readTypes = new HashSet<TypeDeclaration<?>>();
        boolean usePersistenceDefaults = false;
        while (true) {
            while (!pending.isEmpty()) {
                TypeDeclaration<?> t = pending.pop();
                if (!readTypes.add(t)) continue;
                if (!read(index, config, t, packages, feignPackages, mapperScans, entityPackages, jpaRepositories,
                        mongoRepositories, reactiveMongoRepositories, defaultPersistencePackages, named, xmlBeans)) return null;
                if (t instanceof ClassOrInterfaceDeclaration c && !c.isInterface()) {
                    // Spring processes member configurations only for a registered component/configuration outer class.
                    if (hasAnnotation(index, t, "Component", "Configuration", "AutoConfiguration", "SpringBootApplication",
                            "Service", "Repository", "Controller", "RestController", "ControllerAdvice", "RestControllerAdvice")) {
                        for (var member : c.getMembers()) {
                            if (member instanceof ClassOrInterfaceDeclaration nested && !nested.isInterface()
                                    && (hasAnnotation(index, nested, BEANS)
                                        || hasAnnotation(index, nested, "ComponentScan", "Import", "ImportResource", "SpringBootApplication")
                                        || nested.getMethods().stream().anyMatch(m -> Ann.has(m, "Bean")))) pending.add(nested);
                        }
                    }
                    for (var parent : c.getExtendedTypes()) {
                        index.resolveType(parent, t).filter(p -> p.findCompilationUnit().filter(candidates::contains).isPresent())
                                .ifPresent(p -> {
                                    roots.add(p.findCompilationUnit().orElseThrow());
                                    pending.add(p);
                                });
                    }
                }
            }
            for (CompilationUnit cu : candidates) {
                String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                boolean scanned = (beans.contains(cu) || own.contains(cu)) && packages.stream().anyMatch(p -> p.matcher(pkg).matches());
                boolean feignScanned = feignPackages.stream().anyMatch(p -> p.matcher(pkg).matches());
                boolean persistenceDefault = usePersistenceDefaults && defaultPersistencePackages.stream().anyMatch(p -> p.matcher(pkg).matches());
                for (TypeDeclaration<?> t : types(cu)) {
                    if (t.getFullyQualifiedName().filter(named::contains).isPresent()
                            || scanned && Ann.has(t, BEANS) || feignScanned && Ann.has(t, "FeignClient")
                            || mapperScans.stream().anyMatch(scan -> scan.matches(index, t, pkg))
                            || Ann.has(t, "Entity") && (entityPackages.stream().anyMatch(p -> p.matcher(pkg).matches())
                                || entityPackages.isEmpty() && persistenceDefault)
                            || repository(index, t, JPA_REPOSITORIES) && (jpaRepositories.stream().anyMatch(scan -> scan.matches(t, pkg))
                                || jpaRepositories.isEmpty() && persistenceDefault)
                            || repository(index, t, MONGO_REPOSITORIES) && (mongoRepositories.stream().anyMatch(scan -> scan.matches(t, pkg))
                                || mongoRepositories.isEmpty() && persistenceDefault)
                            || repository(index, t, REACTIVE_MONGO_REPOSITORIES) && (reactiveMongoRepositories.stream().anyMatch(scan -> scan.matches(t, pkg))
                                || reactiveMongoRepositories.isEmpty() && persistenceDefault)) {
                        roots.add(cu);
                        if (!readTypes.contains(t)) pending.add(t);
                    }
                }
            }
            if (!pending.isEmpty()) continue;
            if (usePersistenceDefaults) break;
            // Explicit library registrations must be discovered before Boot's default package is used.
            usePersistenceDefaults = true;
        }
        return packages.isEmpty() && feignPackages.isEmpty() && mapperScans.isEmpty() && entityPackages.isEmpty()
                && jpaRepositories.isEmpty() && mongoRepositories.isEmpty()
                && reactiveMongoRepositories.isEmpty()
                && defaultPersistencePackages.isEmpty() && named.isEmpty()
                ? null : library.stream().filter(roots::contains).toList();
    }

    /** Literal Boot sources in main and its source-local helpers; unrelated class literals do not register beans. */
    private static Set<TypeDeclaration<?>> bootSources(JavaIndex index, List<CompilationUnit> own,
                                                       List<CompilationUnit> candidates) {
        var sources = new HashSet<TypeDeclaration<?>>();
        var pending = new ArrayDeque<MethodDeclaration>();
        var seen = new HashSet<MethodDeclaration>();
        own.forEach(cu -> types(cu).stream().filter(t -> t instanceof ClassOrInterfaceDeclaration c && isApp(index, c))
                .forEach(t -> t.getMethodsByName("main").stream().filter(MethodDeclaration::isStatic).forEach(pending::add)));
        while (!pending.isEmpty()) {
            MethodDeclaration method = pending.pop();
            if (!seen.add(method) || method.getBody().isEmpty()) continue;
            for (ObjectCreationExpr creation : method.getBody().orElseThrow().findAll(ObjectCreationExpr.class)) {
                if (List.of("SpringApplication", "SpringApplicationBuilder").contains(creation.getType().getNameAsString()))
                    creation.getArguments().forEach(e -> bootSource(index, e, candidates, sources));
            }
            for (MethodCallExpr call : method.getBody().orElseThrow().findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("run") && !call.getArguments().isEmpty()
                        && call.getScope().map(Object::toString).filter(s -> s.equals("SpringApplication")
                            || s.equals("org.springframework.boot.SpringApplication")).isPresent()) {
                    bootSource(index, call.getArgument(0), candidates, sources);
                } else if (call.getNameAsString().equals("sources")
                        && call.getScope().filter(s -> receiver(index, s, "SpringApplicationBuilder", new HashSet<>())).isPresent()) {
                    call.getArguments().forEach(e -> bootSource(index, e, candidates, sources));
                }
                calledMethods(index, call).stream()
                        .filter(m -> m.findCompilationUnit().filter(candidates::contains).isPresent()).forEach(pending::add);
            }
        }
        return sources;
    }

    private static void bootSource(JavaIndex index, Expression e, List<CompilationUnit> candidates,
                                   Set<TypeDeclaration<?>> sources) {
        if (e instanceof ClassExpr c && c.getType() instanceof ClassOrInterfaceType type) {
            index.resolveType(type, e).filter(t -> t.findCompilationUnit().filter(candidates::contains).isPresent())
                    .ifPresent(sources::add);
        } else if (e instanceof ArrayInitializerExpr array) {
            array.getValues().forEach(value -> bootSource(index, value, candidates, sources));
        } else if (e instanceof ArrayCreationExpr array) {
            array.getInitializer().ifPresent(value -> bootSource(index, value, candidates, sources));
        }
    }

    /** Source-resolved helper calls, including the builder helper commonly used by servlet initializers. */
    private static List<MethodDeclaration> calledMethods(JavaIndex index, MethodCallExpr call) {
        var owner = call.getScope().filter(s -> !(s instanceof ThisExpr))
                .flatMap(s -> index.resolveType(s.toString(), call))
                .or(() -> call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr
                        ? call.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t) : java.util.Optional.empty());
        return owner.stream().flatMap(t -> t.getMethodsByName(call.getNameAsString()).stream())
                .filter(m -> m.getParameters().size() == call.getArguments().size()
                        || !m.getParameters().isEmpty() && m.getParameter(m.getParameters().size() - 1).isVarArgs()
                            && call.getArguments().size() >= m.getParameters().size() - 1).toList();
    }

    private static boolean receiver(JavaIndex index, Expression e, String type, Set<Expression> seen) {
        if (!seen.add(e)) return false;
        if (e instanceof ObjectCreationExpr creation) return creation.getType().getNameAsString().equals(type);
        if (e instanceof EnclosedExpr enclosed) return receiver(index, enclosed.getInner(), type, seen);
        if (e instanceof NameExpr name) {
            if (index.declaredType(name.getNameAsString(), name).filter(type::equals).isPresent()) return true;
            return index.declarationOf(name.getNameAsString(), name).filter(VariableDeclarator.class::isInstance)
                    .map(VariableDeclarator.class::cast).flatMap(VariableDeclarator::getInitializer)
                    .filter(value -> receiver(index, value, type, seen)).isPresent();
        }
        if (e instanceof MethodCallExpr call) return calledMethods(index, call).stream()
                .anyMatch(m -> JavaIndex.simpleName(m.getType()).equals(type))
                || call.getScope().filter(s -> receiver(index, s, type, seen)).isPresent();
        return false;
    }

    private record RepositoryScan(List<Pattern> packages, List<Pattern> excluded) {
        boolean matches(TypeDeclaration<?> t, String pkg) {
            return packages.stream().anyMatch(p -> p.matcher(pkg).matches())
                    && t.getFullyQualifiedName().filter(name -> excluded.stream().anyMatch(p -> p.matcher(name).matches())).isEmpty();
        }
    }

    private record MapperScan(List<Pattern> packages, ClassExpr annotation, ClassExpr marker) {
        boolean matches(JavaIndex index, TypeDeclaration<?> t, String pkg) {
            if (!(t instanceof ClassOrInterfaceDeclaration c) || !c.isInterface()
                    || packages.stream().noneMatch(p -> p.matcher(pkg).matches())) return false;
            if (annotation == null && marker == null) return true;
            if (annotation != null && hasAnnotation(index, t, JavaIndex.simpleName(annotation.getType()))) return true;
            if (marker == null) return false;
            var target = index.resolveType(marker.getType().asString(), marker);
            var pending = new ArrayDeque<ClassOrInterfaceDeclaration>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<ClassOrInterfaceDeclaration, Boolean>());
            pending.add(c);
            while (!pending.isEmpty()) {
                var at = pending.pop();
                if (!seen.add(at)) continue;
                for (var parent : at.getExtendedTypes()) {
                    var source = index.resolveType(parent, at);
                    if (target.isPresent() ? source.orElse(null) == target.get()
                            : parent.getNameAsString().equals(JavaIndex.simpleName(marker.getType()))) return true;
                    source.filter(ClassOrInterfaceDeclaration.class::isInstance)
                            .map(ClassOrInterfaceDeclaration.class::cast).ifPresent(pending::add);
                }
            }
            return false;
        }
    }

    private static boolean repository(JavaIndex index, TypeDeclaration<?> t, Set<String> bases) {
        if (!(t instanceof ClassOrInterfaceDeclaration c) || !c.isInterface() || Ann.has(c, "NoRepositoryBean")) return false;
        var pending = new ArrayDeque<ClassOrInterfaceDeclaration>();
        var seen = new HashSet<ClassOrInterfaceDeclaration>();
        pending.add(c);
        while (!pending.isEmpty()) {
            var at = pending.pop();
            if (!seen.add(at)) continue;
            for (var parent : at.getExtendedTypes()) {
                if (bases.contains(parent.getNameAsString())) return true;
                index.resolveType(parent, at).filter(ClassOrInterfaceDeclaration.class::isInstance)
                        .map(ClassOrInterfaceDeclaration.class::cast).ifPresent(pending::add);
            }
        }
        return false;
    }

    /** Only registered persistence package arguments, their local helpers, and additions to that referenced collection. */
    private static Set<String> entityPackages(JavaIndex index, Expression e, Set<Node> seen) {
        var out = new LinkedHashSet<String>();
        if (!seen.add(e)) return out;
        if (e instanceof ClassExpr c && c.getType() instanceof ClassOrInterfaceType type) {
            index.resolveType(type, e).flatMap(Node::findCompilationUnit).flatMap(CompilationUnit::getPackageDeclaration)
                    .ifPresent(p -> out.add(p.getNameAsString()));
        } else if (e instanceof ArrayInitializerExpr array) {
            array.getValues().forEach(value -> out.addAll(entityPackages(index, value, seen)));
        } else if (e instanceof ArrayCreationExpr array) {
            array.getInitializer().ifPresent(value -> out.addAll(entityPackages(index, value, seen)));
        } else if (e instanceof NameExpr name) {
            index.declarationOf(name.getNameAsString(), name).filter(VariableDeclarator.class::isInstance)
                    .map(VariableDeclarator.class::cast).filter(seen::add).ifPresent(v -> {
                        v.getInitializer().ifPresent(value -> out.addAll(entityPackages(index, value, seen)));
                        v.findAncestor(MethodDeclaration.class).ifPresent(method -> method.findAll(MethodCallExpr.class).stream()
                                .filter(call -> Set.of("add", "addAll").contains(call.getNameAsString()))
                                .filter(call -> call.getScope().orElse(null) instanceof NameExpr scope
                                        && index.declarationOf(scope.getNameAsString(), scope).orElse(null) == v)
                                .forEach(call -> call.getArguments().forEach(value -> out.addAll(entityPackages(index, value, seen)))));
                    });
        } else if (e instanceof MethodCallExpr call) {
            if (call.getNameAsString().equals("toArray")) call.getScope().ifPresent(value -> out.addAll(entityPackages(index, value, seen)));
            else if (Set.of("of", "asList").contains(call.getNameAsString()) && call.getScope().map(Object::toString)
                    .filter(s -> Set.of("List", "Set", "Arrays", "java.util.List", "java.util.Set", "java.util.Arrays").contains(s)).isPresent())
                call.getArguments().forEach(value -> out.addAll(entityPackages(index, value, seen)));
            else if (call.getArguments().isEmpty()) calledMethods(index, call).stream().filter(seen::add)
                    .forEach(method -> method.findAll(ReturnStmt.class).stream()
                            .filter(ret -> ret.findAncestor(MethodDeclaration.class).orElse(null) == method)
                            .forEach(ret -> ret.getExpression().ifPresent(value -> out.addAll(entityPackages(index, value, seen)))));
        }
        Value value = index.eval(e);
        if (value.resolved() && !value.text().isBlank()) out.add(value.text());
        return out;
    }

    private static List<TypeDeclaration<?>> types(CompilationUnit cu) {
        return cu.findAll(TypeDeclaration.class).stream().filter(t -> !(t instanceof AnnotationDeclaration))
                .<TypeDeclaration<?>>map(t -> t).toList();
    }

    /** Adds the packages {@code t} scans and the classes it imports; false when one cannot be read. */
    private static boolean read(JavaIndex index, Config config, TypeDeclaration<?> t, List<Pattern> packages,
                                List<Pattern> feignPackages, List<MapperScan> mapperScans, List<Pattern> entityPackages,
                                List<RepositoryScan> jpaRepositories, List<RepositoryScan> mongoRepositories,
                                List<RepositoryScan> reactiveMongoRepositories,
                                List<Pattern> defaultPersistencePackages, Set<String> named, XmlBeans xmlBeans) {
        String home = t.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration).map(p -> p.getNameAsString()).orElse("");
        var queue = new ArrayDeque<AnnotationExpr>(t.getAnnotations());
        var seen = new HashSet<TypeDeclaration<?>>();
        while (!queue.isEmpty()) {
            AnnotationExpr a = queue.pop();
            String name = a.getName().getIdentifier();
            List<String> scan = null;
            if (name.equals("EnableFeignClients")) {
                List<Expression> clients = items(Ann.attr(a, "clients").orElse(null));
                if (!clients.isEmpty()) {
                    for (Expression e : clients) if (e instanceof ClassExpr c && c.getType() instanceof ClassOrInterfaceType type)
                        index.resolveType(type, a).flatMap(TypeDeclaration::getFullyQualifiedName).ifPresent(named::add);
                    continue; // explicit clients replace Feign's package scan
                }
                var scanPackages = strings(config, a, "value", "basePackages");
                if (scanPackages == null) continue; // unknown Feign roots must not load every library client
                scanPackages.removeIf(String::isBlank);
                for (Expression e : items(Ann.attr(a, "basePackageClasses").orElse(null))) {
                    if (e instanceof ClassExpr c && c.getType() instanceof ClassOrInterfaceType type)
                        index.resolveType(type, a).flatMap(Node::findCompilationUnit)
                                .flatMap(CompilationUnit::getPackageDeclaration).ifPresent(p -> scanPackages.add(p.getNameAsString()));
                }
                if (scanPackages.isEmpty()) scanPackages.add(home);
                scanPackages.forEach(p -> feignPackages.add(glob(p)));
                continue;
            } else if (name.equals("EnableJpaRepositories") || name.equals("EnableMongoRepositories")
                    || name.equals("EnableReactiveMongoRepositories") || name.equals("EntityScan")) {
                var registered = strings(config, a, "value", "basePackages");
                if (registered == null) return false;
                for (Expression e : items(Ann.attr(a, "basePackageClasses").orElse(null)))
                    registered.addAll(entityPackages(index, e, Collections.newSetFromMap(new IdentityHashMap<>())));
                if (registered.isEmpty()) registered.add(home);
                if (name.equals("EntityScan")) registered.forEach(p -> entityPackages.add(glob(p)));
                else {
                    var excluded = new ArrayList<Pattern>();
                    for (Expression e : items(Ann.attr(a, "excludeFilters").orElse(null))) {
                        if (e instanceof AnnotationExpr filter && Ann.attr(filter, "type").map(Object::toString)
                                .filter(type -> type.endsWith("REGEX")).isPresent()) {
                            var patterns = strings(config, filter, "pattern");
                            if (patterns == null) return false;
                            try {
                                patterns.forEach(p -> excluded.add(Pattern.compile(p)));
                            } catch (java.util.regex.PatternSyntaxException invalid) {
                                return false;
                            }
                        }
                    }
                    var repositories = switch (name) {
                        case "EnableMongoRepositories" -> mongoRepositories;
                        case "EnableReactiveMongoRepositories" -> reactiveMongoRepositories;
                        default -> jpaRepositories;
                    };
                    repositories.add(new RepositoryScan(registered.stream().map(Discovery::glob).toList(), excluded));
                }
                continue;
            } else if (name.equals("MapperScans")) {
                items(Ann.attr(a, "value").orElse(null)).stream().filter(AnnotationExpr.class::isInstance)
                        .map(AnnotationExpr.class::cast).forEach(queue::add);
                continue;
            } else if (name.equals("MapperScan")) {
                var scanPackages = strings(config, a, "value", "basePackages");
                if (scanPackages == null) return false;
                for (Expression e : items(Ann.attr(a, "basePackageClasses").orElse(null))) {
                    if (!(e instanceof ClassExpr c)) return false;
                    var pkg = index.resolveType(c.getType().asString(), c).flatMap(Node::findCompilationUnit)
                            .flatMap(CompilationUnit::getPackageDeclaration);
                    if (pkg.isEmpty()) return false;
                    scanPackages.add(pkg.get().getNameAsString());
                }
                scanPackages.removeIf(String::isBlank);
                if (scanPackages.isEmpty()) scanPackages.add(home);
                Expression annotation = Ann.attr(a, "annotationClass").orElse(null);
                Expression marker = Ann.attr(a, "markerInterface").orElse(null);
                if (annotation != null && !(annotation instanceof ClassExpr) || marker != null && !(marker instanceof ClassExpr)) return false;
                if (annotation instanceof ClassExpr c && JavaIndex.simpleName(c.getType()).equals("Annotation")) annotation = null;
                if (marker instanceof ClassExpr c && JavaIndex.simpleName(c.getType()).equals("Class")) marker = null;
                mapperScans.add(new MapperScan(scanPackages.stream().map(Discovery::glob).toList(),
                        (ClassExpr) annotation, (ClassExpr) marker));
                continue;
            } else if (name.equals("ComponentScan")) {
                scan = strings(config, a, "value", "basePackages");
            } else if (name.equals("SpringBootApplication")) {
                defaultPersistencePackages.add(glob(home));
                if (Ann.has(t, "ComponentScan")) continue; // an explicit @ComponentScan replaces the default one
                scan = strings(config, a, "scanBasePackages");
            } else if (name.equals("EnableAutoConfiguration")) {
                defaultPersistencePackages.add(glob(home));
                continue;
            } else if (name.equals("ImportResource")) {
                var resources = strings(config, a, "value", "locations");
                if (resources != null) resources.forEach(resource -> named.addAll(xmlBeans.classes(resource)));
                continue;
            } else if (name.equals("Import")) {
                for (Expression e : items(Ann.attr(a, "value").orElse(null))) {
                    if (e instanceof ClassExpr c && c.getType() instanceof ClassOrInterfaceType type) {
                        index.resolveType(type, a).flatMap(TypeDeclaration::getFullyQualifiedName).ifPresent(named::add);
                    }
                }
                continue;
            } else {
                index.resolveType(a.getNameAsString(), a).filter(d -> d instanceof AnnotationDeclaration && seen.add(d))
                        .filter(Discovery::runtimeRetention)
                        .ifPresent(d -> d.getAnnotations().forEach(meta ->
                                queue.add(feignAnnotation(index, a, (AnnotationDeclaration) d, meta))));
                continue;
            }
            if (scan == null) return false;
            for (Expression e : items(Ann.attr(a, name.equals("ComponentScan") ? "basePackageClasses" : "scanBasePackageClasses").orElse(null))) {
                if (!(e instanceof ClassExpr c)) return false;
                var pkg = index.resolveType(c.getType().asString(), c).flatMap(Node::findCompilationUnit)
                        .flatMap(CompilationUnit::getPackageDeclaration);
                if (pkg.isEmpty()) return false;
                scan.add(pkg.get().getNameAsString());
            }
            if (scan.isEmpty()) scan = List.of(home);
            scan.forEach(p -> packages.add(glob(p)));
        }
        for (MethodDeclaration method : t.getMethods()) {
            if (!Ann.has(method, "Bean")) continue;
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (call.getScope().filter(scope ->
                        call.getNameAsString().equals("packages")
                                && receiver(index, scope, "EntityManagerFactoryBuilder", new HashSet<>())
                        || call.getNameAsString().equals("setPackagesToScan")
                                && (receiver(index, scope, "LocalSessionFactoryBean", new HashSet<>())
                                    || receiver(index, scope, "LocalContainerEntityManagerFactoryBean", new HashSet<>())))
                        .isPresent())
                    call.getArguments().forEach(e -> entityPackages(index, e, Collections.newSetFromMap(new IdentityHashMap<>()))
                            .forEach(p -> entityPackages.add(glob(p))));
            }
        }
        return true;
    }

    /** Apply a composed annotation's explicit Feign aliases without changing the indexed source AST. */
    private static AnnotationExpr feignAnnotation(JavaIndex index, AnnotationExpr use,
                                                   AnnotationDeclaration declaration, AnnotationExpr meta) {
        if (!meta.getName().getIdentifier().equals("EnableFeignClients")) return meta;
        var effective = new NormalAnnotationExpr();
        effective.setName(meta.getName().clone());
        if (meta instanceof NormalAnnotationExpr normal) normal.getPairs().forEach(p -> effective.getPairs().add(p.clone()));
        else if (meta instanceof SingleMemberAnnotationExpr single) effective.addPair("value", single.getMemberValue().clone());
        for (var member : declaration.getMembers()) {
            if (!(member instanceof AnnotationMemberDeclaration attribute)) continue;
            var alias = Ann.find(attribute, "AliasFor");
            if (alias.isEmpty() || !Ann.attr(alias.get(), "annotation").map(Object::toString)
                    .filter(x -> x.endsWith("EnableFeignClients.class")).isPresent()) continue;
            String target = Ann.attr(alias.get(), "attribute", "value").filter(Expression::isStringLiteralExpr)
                    .map(e -> e.asStringLiteralExpr().asString()).filter(x -> !x.isBlank()).orElse(attribute.getNameAsString());
            var value = Ann.attr(use, attribute.getNameAsString()).or(attribute::getDefaultValue);
            if (value.isEmpty()) continue;
            Expression copy = value.get().clone();
            for (ClassExpr literal : copy.findAll(ClassExpr.class)) {
                if (literal.getType() instanceof ClassOrInterfaceType type)
                    index.resolveType(type, use).flatMap(TypeDeclaration::getFullyQualifiedName)
                            .ifPresent(fqn -> literal.setType(com.github.javaparser.StaticJavaParser.parseType(fqn)));
            }
            // FeignClientsRegistrar unions value and basePackages; an override replaces only its target.
            effective.getPairs().removeIf(p -> p.getNameAsString().equals(target));
            effective.addPair(target, copy);
        }
        return effective;
    }

    /** String-literal attribute values with {@code ${}} resolved; null when one is not a literal or does not resolve. */
    private static List<String> strings(Config config, AnnotationExpr a, String... names) {
        var out = new ArrayList<String>();
        for (String name : names) {
            for (Expression e : items(Ann.attr(a, name).orElse(null))) {
                if (!(e instanceof StringLiteralExpr s)) return null;
                Value v = config.resolve(s.asString());
                if (!v.resolved()) return null;
                out.add(v.text());
            }
        }
        return out;
    }

    private static List<Expression> items(Expression e) {
        if (e == null) return List.of();
        return e instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(e);
    }

    /** An Ant-style package pattern ({@code a.*.mapper}, {@code a.**.dao}) and its subpackages. */
    private static Pattern glob(String pkg) {
        // ".**" goes through a placeholder so the single-"*" pass does not rewrite the "*" it produces
        String re = pkg.strip().replace(".", "\\.").replace("$", "\\$").replace("\\.**", "\0")
                .replace("*", "[^.]*").replace("\0", "(\\..*)?");
        return Pattern.compile(re + "(\\..*)?");
    }

    /** Auto-configuration classes a library module registers in {@code META-INF}. */
    private static List<String> autoConfigs(Path module) {
        var out = new ArrayList<String>();
        Path meta = module.resolve("src/main/resources/META-INF");
        try {
            Matcher m = FACTORIES.matcher(Config.readText(meta.resolve("spring.factories")).replaceAll("\\\\\\R", ""));
            while (m.find()) for (String c : m.group(1).split(",")) out.add(c.strip().replace('$', '.'));
        } catch (IOException e) {
            // no spring.factories
        }
        try {
            for (String line : Config.readText(meta.resolve("spring/" + IMPORTS)).split("\\R")) {
                if (!line.isBlank() && !line.strip().startsWith("#")) out.add(line.strip().replace('$', '.'));
            }
        } catch (IOException e) {
            // no AutoConfiguration.imports
        }
        return out;
    }

    /** Referenced value types, class superclasses and static scopes; implemented interfaces are handled separately. */
    private static List<TypeDeclaration<?>> referencedTypes(JavaIndex index, CompilationUnit cu) {
        var names = new LinkedHashMap<String, Node>();
        for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
            if (!(t.getParentNode().orElse(null) instanceof TypeDeclaration<?>)
                    || t.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration c
                        && !c.isInterface() && c.getExtendedTypes().contains(t)) names.putIfAbsent(t.getNameWithScope(), t);
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

    /**
     * The files of a config repo for {@code app}: {@code application*} then {@code <app>*}, profile files on top,
     * each read from the repo root and its {@code <app>} subfolder, or from the server's search paths when given.
     */
    private static Config remoteConfig(Repo repo, Path root, List<String> searchPaths, String app, String profile) {
        var dirs = new ArrayList<Path>();
        if (searchPaths.isEmpty()) {
            dirs.add(root);
            dirs.add(root.resolve(app));
        }
        searchPaths.forEach(p -> dirs.addAll(expand(root, p.replace("{application}", app))));
        var files = new ArrayList<Path>();
        for (String base : List.of("application", app)) {
            for (String suffix : List.of("", "-" + profile)) {
                for (Path dir : dirs) for (String ext : EXTENSIONS) {
                    Path file = dir.resolve(base + suffix + "." + ext);
                    if (inside(root, file)) files.add(file);
                }
            }
        }
        return Config.load(repo, files, profile);
    }

    /**
     * The values of a config server setting ({@code git.searchpaths}, {@code native.searchlocations}: lower case,
     * no dashes), whatever the binding style (kebab, camel, list or comma separated). Values that do not resolve
     * are dropped, and so are non-classpath native locations: they point outside the repo.
     */
    // shortcut: a config server in another scanned repo than its services is not consulted, upgrade by passing --config-repo.
    private static List<String> locations(Config server, String key, boolean classpathOnly) {
        String prefix = "spring.cloud.config.server.";
        var out = new ArrayList<String>();
        server.withPrefix(prefix).forEach((k, e) -> {
            String norm = k.substring(prefix.length()).replaceAll("\\[\\d+]$", "").replaceAll("[-_]", "").toLowerCase();
            if (!norm.equals(key) || e.value() == null) return;
            for (String p : e.value().split(",")) {
                Value v = server.resolve(p.strip());
                String text = v.text().strip();
                if (p.isBlank() || !v.resolved() || classpathOnly && !text.startsWith("classpath:")) continue;
                out.add(classpathOnly ? text.substring("classpath:".length()) : text);
            }
        });
        return out;
    }

    /** A search path under {@code root}: a leading '/' is dropped and {@code *} in the last segment matches folder names. */
    private static List<Path> expand(Path root, String pattern) {
        String rel = pattern.replaceAll("^/+", "");
        int slash = rel.lastIndexOf('/');
        String last = rel.substring(slash + 1); // resolved apart: Windows rejects '*' in a path
        Path parent = slash < 0 ? root : root.resolve(rel.substring(0, slash));
        if (!inside(root, parent)) return List.of();
        if (!last.contains("*")) {
            Path candidate = parent.resolve(last);
            return inside(root, candidate) ? List.of(candidate) : List.of();
        }
        var glob = Pattern.compile(Pattern.quote(last).replace("*", "\\E.*\\Q"));
        try (var dirs = Files.newDirectoryStream(parent, Files::isDirectory)) {
            var out = new ArrayList<Path>();
            for (Path d : dirs) if (glob.matcher(d.getFileName().toString()).matches() && inside(root, d)) out.add(d);
            out.sort(null);
            return out;
        } catch (IOException e) {
            return List.of(); // no such folder
        }
    }

    /** Search locations and application names may only select files inside the configured checkout. */
    static boolean inside(Path root, Path path) {
        Path base = root.toAbsolutePath().normalize();
        Path candidate = path.toAbsolutePath().normalize();
        if (!candidate.startsWith(base)) return false;
        try {
            return !Files.exists(candidate) || candidate.toRealPath().startsWith(base.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }
}
