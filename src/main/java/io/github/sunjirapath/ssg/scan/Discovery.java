package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import io.github.sunjirapath.ssg.model.Value;

import java.io.IOException;
import java.nio.file.Files;
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
            "Mapper", "ControllerAdvice", "RestControllerAdvice"};
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
            List<CompilationUnit> units = apps.size() == 1 ? index.units()
                    : used(index, module, libraries, modules.direct(module), apps.keySet(), refs, config);
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
        if (hasSpringBootApplication(index, c)) return true;
        return c.getMethodsByName("main").stream().filter(m -> m.isStatic()).flatMap(m -> m.getBody().stream()).anyMatch(b ->
                b.findAll(ObjectCreationExpr.class).stream().anyMatch(o -> o.getType().getNameAsString().startsWith("SpringApplication"))
                || b.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals("run")
                        && call.getScope().map(Object::toString).filter(x -> x.endsWith("SpringApplication")).isPresent()));
    }

    /** Recognize Spring Boot's annotation directly or through source-local composed annotations. */
    private static boolean hasSpringBootApplication(JavaIndex index, ClassOrInterfaceDeclaration c) {
        var pending = new ArrayDeque<AnnotationExpr>(c.getAnnotations());
        var seen = new HashSet<TypeDeclaration<?>>();
        while (!pending.isEmpty()) {
            AnnotationExpr annotation = pending.pop();
            if (annotation.getName().getIdentifier().equals("SpringBootApplication")) return true;
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
     * build.gradle (transitively; {@link #BEANS} classes, plus controllers from direct dependencies only), and
     * the library sources (in no app's module) those use: the files declaring a type they reference, directly or
     * through other library files. So a shared module's Feign clients and DB access count for every service
     * that pulls them in, while its controllers count only where the launcher module depends on it, not in every
     * service that reaches it through some starter. A type the service implements or extends is left out,
     * because a local implementation of a {@code @FeignClient} interface is the server, not a client.
     * A library bean counts only if Spring would load it: see {@link #loaded}.
     */
    private static List<CompilationUnit> used(JavaIndex index, Path module, Set<Path> libraries, Set<Path> direct,
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
                if (cu.findAll(TypeDeclaration.class).stream().anyMatch(t ->
                        Ann.has(t, BEANS) || direct.contains(m) && Ann.has(t, "RestController", "Controller"))) beans.add(cu);
            }
        }
        List<CompilationUnit> roots = loaded(index, config, own, library, beans, libraries);
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
                if (implemented.contains(t)) continue;
                CompilationUnit cu = t.findCompilationUnit().orElseThrow();
                if (!appModules.contains(index.moduleOf(index.fileOf(cu))) && units.add(cu)) queue.push(cu);
            }
        }
        return List.copyOf(units);
    }

    /**
     * The library files Spring would load for the service: beans in a component-scanned package (the
     * {@code @SpringBootApplication} package or its {@code scanBasePackages}, {@code @ComponentScan},
     * {@code @MapperScan}), the auto-configurations the libraries list in {@code META-INF}, and
     * {@code @Import}ed classes, each also through meta-annotations and through the classes it loads in turn.
     * Null when a scan attribute cannot be read, or nothing names a package: the caller then counts every bean.
     */
    // shortcut: excludeFilters, exclude=, @Conditional* and ImportSelector results are ignored, and
    // basePackageClasses or non-literal packages fall back to every bean; read them if a service over-counts.
    private static List<CompilationUnit> loaded(JavaIndex index, Config config, List<CompilationUnit> own,
                                                List<CompilationUnit> library, Set<CompilationUnit> beans, Set<Path> libraries) {
        var packages = new ArrayList<Pattern>();
        var feignPackages = new ArrayList<Pattern>();
        var named = new HashSet<String>();
        libraries.forEach(lib -> named.addAll(autoConfigs(lib)));
        var pending = new ArrayDeque<TypeDeclaration<?>>();
        own.forEach(cu -> types(cu).stream().filter(t -> t instanceof ClassOrInterfaceDeclaration c && isApp(index, c))
                .forEach(pending::add));
        var candidates = new ArrayList<>(own);
        candidates.addAll(library);
        var roots = new HashSet<CompilationUnit>();
        while (!pending.isEmpty()) {
            while (!pending.isEmpty()) if (!read(index, config, pending.pop(), packages, feignPackages, named)) return null;
            for (CompilationUnit cu : candidates) {
                String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                boolean hit = types(cu).stream().anyMatch(t -> t.getFullyQualifiedName().filter(named::contains).isPresent())
                        || (beans.contains(cu) || own.contains(cu) && types(cu).stream().anyMatch(t -> Ann.has(t, BEANS)))
                            && packages.stream().anyMatch(p -> p.matcher(pkg).matches())
                        || types(cu).stream().anyMatch(t -> Ann.has(t, "FeignClient"))
                            && feignPackages.stream().anyMatch(p -> p.matcher(pkg).matches());
                if (hit && roots.add(cu)) pending.addAll(types(cu));
            }
        }
        return packages.isEmpty() && feignPackages.isEmpty() && named.isEmpty()
                ? null : library.stream().filter(roots::contains).toList();
    }

    private static List<TypeDeclaration<?>> types(CompilationUnit cu) {
        return cu.findAll(TypeDeclaration.class).stream().filter(t -> !(t instanceof AnnotationDeclaration))
                .<TypeDeclaration<?>>map(t -> t).toList();
    }

    /** Adds the packages {@code t} scans and the classes it imports; false when one cannot be read. */
    private static boolean read(JavaIndex index, Config config, TypeDeclaration<?> t, List<Pattern> packages,
                                List<Pattern> feignPackages, Set<String> named) {
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
            } else if (name.equals("ComponentScan") || name.equals("MapperScan")) {
                if (Ann.attr(a, "basePackageClasses").isPresent()) return false;
                scan = strings(config, a, "value", "basePackages");
            } else if (name.equals("SpringBootApplication")) {
                if (Ann.has(t, "ComponentScan")) continue; // an explicit @ComponentScan replaces the default one
                if (Ann.attr(a, "scanBasePackageClasses").isPresent()) return false;
                scan = strings(config, a, "scanBasePackages");
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
            if (scan.isEmpty()) scan = List.of(home);
            scan.forEach(p -> packages.add(glob(p)));
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
    private static boolean inside(Path root, Path path) {
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
