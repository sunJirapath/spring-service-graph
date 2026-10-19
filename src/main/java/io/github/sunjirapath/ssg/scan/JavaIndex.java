package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Value;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * All parsed Java sources of one repo, plus name lookup and constant folding.
 * No symbol solver: names are resolved through locals, fields (including inherited ones),
 * imports and the same package, which covers how constants are written in practice.
 */
public final class JavaIndex {

    /** A file the parser rejected or only partly understood. */
    public record Problem(Path file, int line, String message) {}

    private static final int MAX_DEPTH = 24;

    private final Repo repo;
    private final List<CompilationUnit> units = new ArrayList<>();
    private final Map<String, List<TypeDeclaration<?>>> typesByFqn = new HashMap<>(); // same FQN may live in several modules
    private final Map<CompilationUnit, Path> files = new HashMap<>();
    private final List<Problem> problems = new ArrayList<>();

    private JavaIndex(Repo repo) {
        this.repo = repo;
    }

    public static JavaIndex build(Repo repo) {
        var idx = new JavaIndex(repo);
        var parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                // comments are never read; tokens are needed while parsing, node positions (evidence lines) come from them
                .setAttributeComments(false));
        var shared = new HashMap<Object, Object>(); // equal positions and names, one copy each
        try {
            for (Path f : repo.files(repo.root()).stream().filter(idx::isMainSource).toList()) {
                var result = parser.parse(f);
                result.getProblems().forEach(p -> idx.problems.add(new Problem(f,
                        p.getLocation().flatMap(l -> l.toRange()).map(r -> r.begin.line).orElse(1),
                        p.getMessage().lines().findFirst().orElse("parse error"))));
                result.getResult().ifPresent(cu -> idx.add(f, cu, shared));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return idx;
    }

    /** Main sources and generated sources (openapi-generator output); never tests. */
    private boolean isMainSource(Path f) {
        String p = rel(f);
        if (!p.endsWith(".java") || p.contains("/.git/") || p.contains("/node_modules/") || p.contains("/src/test/")) {
            return false;
        }
        boolean inBuildDir = p.contains("/build/") || p.contains("/target/");
        return inBuildDir ? p.contains("generated") : p.contains("/src/main/java/");
    }

    /** Repo-relative path with a leading slash, so markers never match the checkout location. */
    private String rel(Path f) {
        return "/" + repo.relative(f);
    }

    /** The module directory a source file belongs to. */
    public Path moduleOf(Path file) {
        String p = rel(file);
        for (String marker : List.of("/src/main/java/", "/build/", "/target/")) {
            int i = p.indexOf(marker);
            if (i >= 0) return repo.root().resolve(p.substring(0, i).replaceFirst("^/", "")).normalize();
        }
        return file.getParent();
    }

    private void add(Path file, CompilationUnit cu, Map<Object, Object> shared) {
        var ranges = new HashMap<Range, Range>(); // a node and its only child often span the same text
        cu.walk(n -> compact(n, shared, ranges));
        units.add(cu);
        files.put(cu, file);
        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
            t.getFullyQualifiedName().ifPresent(fqn -> typesByFqn.computeIfAbsent(fqn, k -> new ArrayList<>()).add(t));
        }
    }

    // nothing edits a tree once indexed, so empty lists can be one shared instance
    // shortcut: reflects into JavaParser private fields (fails fast at startup if renamed), recheck on a JavaParser upgrade
    private static final ArrayList<Object> NONE = new ArrayList<>(0);
    private static final Field CHILDREN = field(Node.class, "childNodes"), ORPHANS = field(Node.class, "orphanComments"),
            OBSERVERS = field(Node.class, "observers"), INNER = field(NodeList.class, "innerList"),
            LIST_OBSERVERS = field(NodeList.class, "observers"), SIMPLE_ID = field(SimpleName.class, "identifier"),
            NAME_ID = field(Name.class, "identifier");

    /**
     * Shrinks one parsed node in place: keeps its range but drops its token list (the tokens, whitespace included,
     * were most of the heap), shares equal ranges, positions and names, and swaps the per-node lists
     * (three per node, mostly empty; comments are never attributed) for one shared empty list.
     */
    @SuppressWarnings("unchecked")
    private static void compact(Node n, Map<Object, Object> shared, Map<Range, Range> ranges) {
        var range = n.getRange(); // absent on the few nodes the parser makes up
        n.setTokenRange(null);
        range.ifPresent(r -> n.setRange(ranges.computeIfAbsent(r, k -> new Range(one(shared, k.begin), one(shared, k.end)))));
        try {
            // the setters skip equal values, so share names through the field
            if (n instanceof SimpleName s) SIMPLE_ID.set(n, one(shared, s.getIdentifier()));
            if (n instanceof Name s) NAME_ID.set(n, one(shared, s.getIdentifier()));
            var children = (ArrayList<Object>) CHILDREN.get(n);
            if (children.isEmpty()) CHILDREN.set(n, NONE); else children.trimToSize();
            ORPHANS.set(n, NONE);
            OBSERVERS.set(n, NONE);
            for (var p : n.getMetaModel().getAllPropertyMetaModels()) {
                if (p.isNodeList() && p.getValue(n) instanceof NodeList<?> list) {
                    var inner = (List<Object>) INNER.get(list);
                    INNER.set(list, List.copyOf(inner)); // immutable lists of up to two hold their items without an array
                    LIST_OBSERVERS.set(list, List.of());
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T one(Map<Object, Object> shared, T value) {
        return (T) shared.computeIfAbsent(value, k -> k);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("javaparser changed: no " + type.getSimpleName() + "." + name, e);
        }
    }

    public Repo repo() { return repo; }
    public List<CompilationUnit> units() { return units; }
    public List<Problem> problems() { return problems; }
    public Path fileOf(CompilationUnit cu) { return files.get(cu); }

    public Evidence evidence(Node n) {
        CompilationUnit cu = n.findCompilationUnit().orElseThrow();
        return repo.evidence(files.get(cu), n.getBegin().map(p -> p.line).orElse(1));
    }

    public boolean isGenerated(CompilationUnit cu) {
        return !rel(files.get(cu)).contains("/src/main/java/");
    }

    // ---------------------------------------------------------------- types

    /** Resolves a (possibly dotted) type name as seen from {@code context}. */
    public Optional<TypeDeclaration<?>> resolveType(String name, Node context) {
        TypeDeclaration<?> exact = type(name, context);
        if (exact != null) return Optional.of(exact);
        int dot = name.indexOf('.');
        String head = dot < 0 ? name : name.substring(0, dot);
        Optional<TypeDeclaration<?>> t = resolveSimple(head, context);
        if (t.isEmpty() || dot < 0) return t;
        String fqn = t.get().getFullyQualifiedName().orElse("") + name.substring(dot);
        return Optional.ofNullable(type(fqn, context));
    }

    public Optional<TypeDeclaration<?>> resolveType(ClassOrInterfaceType type, Node context) {
        return resolveType(type.getNameWithScope(), context);
    }

    private Optional<TypeDeclaration<?>> resolveSimple(String simple, Node context) {
        // enclosing and member types first
        for (Node n = context; n != null; n = n.getParentNode().orElse(null)) {
            if (n instanceof TypeDeclaration<?> td) {
                if (td.getNameAsString().equals(simple)) return Optional.of(td);
                TypeDeclaration<?> member = td.getFullyQualifiedName().map(f -> type(f + "." + simple, context)).orElse(null);
                if (member != null) return Optional.of(member);
            }
        }
        CompilationUnit cu = context.findCompilationUnit().orElse(null);
        if (cu == null) return Optional.empty();
        // Java precedence: single-type imports, then the same package, then on-demand imports
        var single = new ArrayList<String>();
        var onDemand = new ArrayList<String>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic()) continue;
            if (imp.isAsterisk()) onDemand.add(imp.getNameAsString() + "." + simple);
            else if (imp.getName().getIdentifier().equals(simple)) single.add(imp.getNameAsString());
        }
        single.add(cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + simple);
        single.addAll(onDemand);
        return single.stream().<TypeDeclaration<?>>map(n -> type(n, context)).filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * The type with this FQN; when several modules declare it (copies, per-service generated code),
     * the one in the same module as {@code context}.
     */
    private TypeDeclaration<?> type(String fqn, Node context) {
        var all = typesByFqn.get(fqn);
        if (all == null) return null;
        if (all.size() == 1) return all.getFirst();
        Path module = moduleOfNode(context);
        return all.stream().filter(t -> module != null && module.equals(moduleOfNode(t))).findFirst().orElse(all.getFirst());
    }

    /** {@code t}, or a same-FQN copy of it declared in one of {@code modules} when {@code t} is not. */
    public TypeDeclaration<?> preferring(TypeDeclaration<?> t, Set<Path> modules) {
        var all = t.getFullyQualifiedName().map(typesByFqn::get).orElse(List.of());
        if (all.size() < 2 || modules.contains(moduleOfNode(t))) return t;
        return all.stream().filter(c -> modules.contains(moduleOfNode(c))).findFirst().orElse(t);
    }

    private Path moduleOfNode(Node n) {
        return n.findCompilationUnit().map(files::get).map(this::moduleOf).orElse(null);
    }

    /** Super types (extends + implements) that exist in this repo's sources. */
    public List<TypeDeclaration<?>> superTypes(TypeDeclaration<?> t) {
        if (!(t instanceof ClassOrInterfaceDeclaration c)) return List.of();
        var out = new ArrayList<TypeDeclaration<?>>();
        for (var st : concat(c.getExtendedTypes(), c.getImplementedTypes())) resolveType(st, t).ifPresent(out::add);
        return out;
    }

    private static List<ClassOrInterfaceType> concat(NodeList<ClassOrInterfaceType> a, NodeList<ClassOrInterfaceType> b) {
        var l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    // ---------------------------------------------------------------- declarations

    /** The local variable, parameter or field that {@code name} refers to at {@code context}. */
    public Optional<Node> declarationOf(String name, Node context) {
        var use = context.getBegin().orElse(null);
        for (Node n = context; n != null; n = n.getParentNode().orElse(null)) {
            switch (n) {
                case BlockStmt b -> {
                    for (var st : b.getStatements()) {
                        // a local is only in scope after its declaration
                        boolean before = use == null || st.getEnd().map(end -> end.isBefore(use)).orElse(true);
                        if (before && st instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr v) {
                            var hit = v.getVariables().stream().filter(d -> d.getNameAsString().equals(name)).findFirst();
                            if (hit.isPresent()) return Optional.of(hit.get());
                        }
                    }
                }
                case com.github.javaparser.ast.stmt.ForStmt f -> {
                    for (var init : f.getInitialization()) {
                        if (!(init instanceof VariableDeclarationExpr v)) continue;
                        var hit = v.getVariables().stream().filter(d -> d.getNameAsString().equals(name)).findFirst();
                        if (hit.isPresent()) return Optional.of(hit.get());
                    }
                }
                case CallableDeclaration<?> c -> {
                    var hit = c.getParameters().stream().filter(p -> p.getNameAsString().equals(name)).findFirst();
                    if (hit.isPresent()) return Optional.of(hit.get());
                }
                case LambdaExpr l -> {
                    var hit = l.getParameters().stream().filter(p -> p.getNameAsString().equals(name)).findFirst();
                    if (hit.isPresent()) return Optional.of(hit.get());
                }
                case TypeDeclaration<?> t -> {
                    var hit = fieldOf(t, name, 0);
                    if (hit.isPresent()) return hit.map(v -> (Node) v);
                    if (t instanceof RecordDeclaration r) {
                        var comp = r.getParameters().stream().filter(p -> p.getNameAsString().equals(name)).findFirst();
                        if (comp.isPresent()) return Optional.of(comp.get());
                    }
                }
                default -> { }
            }
        }
        return Optional.empty();
    }

    /** A field declared in {@code t} or inherited from a super type found in sources. */
    public Optional<VariableDeclarator> fieldOf(TypeDeclaration<?> t, String name, int depth) {
        for (FieldDeclaration f : t.getFields()) {
            for (VariableDeclarator v : f.getVariables()) if (v.getNameAsString().equals(name)) return Optional.of(v);
        }
        if (depth > 64) return Optional.empty(); // guard against cyclic extends in broken code
        for (var st : superTypes(t)) {
            var hit = fieldOf(st, name, depth + 1);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    /** Simple name of the declared type of {@code name} at {@code context}, e.g. "RestTemplate". */
    public Optional<String> declaredType(String name, Node context) {
        return declarationOf(name, context).map(d -> switch (d) {
            case VariableDeclarator v -> simpleName(v.getType());
            case Parameter p -> simpleName(p.getType());
            default -> "";
        });
    }

    public static String simpleName(Type t) {
        return t instanceof ClassOrInterfaceType c ? c.getName().getIdentifier() : t.asString();
    }

    // ---------------------------------------------------------------- folding

    /** Folds an expression to a string value. Property placeholders are left for {@link Config}. */
    public Value eval(Expression e) {
        return eval(e, 0);
    }

    /** Like {@link #eval} but expands array initializers: {@code {"a", B.C}} gives two values. */
    public List<Value> evalAll(Expression e) {
        if (e instanceof ArrayInitializerExpr a) return a.getValues().stream().map(this::eval).toList();
        return List.of(eval(e));
    }

    private Value eval(Expression e, int depth) {
        if (depth > MAX_DEPTH) return Value.unresolved(e.toString());
        return switch (e) {
            case StringLiteralExpr s -> Value.exact(s.asString());
            case TextBlockLiteralExpr t -> Value.exact(t.asString());
            case CharLiteralExpr c -> Value.exact(c.asChar() + "");
            case IntegerLiteralExpr i -> Value.exact(i.getValue());
            case LongLiteralExpr l -> Value.exact(l.getValue().replaceAll("[lL]$", ""));
            case BooleanLiteralExpr b -> Value.exact(b.getValue() + "");
            // syntactic nesting is finite, so only name and call hops count against MAX_DEPTH (cycle guard)
            case EnclosedExpr en -> eval(en.getInner(), depth);
            case CastExpr c -> eval(c.getExpression(), depth);
            case BinaryExpr b when b.getOperator() == BinaryExpr.Operator.PLUS ->
                    raw(eval(b.getLeft(), depth).concat(eval(b.getRight(), depth), b.toString()), e);
            case NameExpr n -> evalDeclaration(declarationOf(n.getNameAsString(), n), n, depth)
                    .orElseGet(() -> staticImport(n, depth));
            case FieldAccessExpr f -> evalFieldAccess(f, depth);
            case MethodCallExpr m -> evalCall(m, depth);
            default -> Value.unresolved(e.toString());
        };
    }

    private static Value raw(Value v, Expression e) {
        return new Value(v.text(), v.confidence(), e.toString(), v.via());
    }

    private Optional<Value> evalDeclaration(Optional<Node> decl, Expression use, int depth) {
        if (decl.isEmpty()) return Optional.empty();
        Node d = decl.get();
        Evidence at = evidence(d);
        // @Value("${...}") on a field or parameter: the placeholder text is the value
        NodeWithAnnotations<?> annotated = d instanceof Parameter p ? p
                : d.getParentNode().filter(FieldDeclaration.class::isInstance).map(FieldDeclaration.class::cast).orElse(null);
        if (annotated != null) {
            var v = Ann.find(annotated, "Value").flatMap(a -> Ann.attr(a, "value"));
            if (v.isPresent()) return Optional.of(raw(eval(v.get(), depth + 1).withConfidence(Confidence.RESOLVED, at), use));
        }
        if (d instanceof VariableDeclarator v && v.getInitializer().isPresent()) {
            // shortcut: a non-final field or local reassigned later still folds to its initializer.
            return Optional.of(raw(eval(v.getInitializer().get(), depth + 1).withConfidence(Confidence.RESOLVED, at), use));
        }
        return Optional.of(Value.unresolved(use.toString()));
    }

    private Value staticImport(NameExpr n, int depth) {
        String name = n.getNameAsString();
        CompilationUnit cu = n.findCompilationUnit().orElse(null);
        if (cu == null) return Value.unresolved(name);
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isStatic()) continue;
            String type;
            if (imp.isAsterisk()) type = imp.getNameAsString();
            else if (imp.getName().getIdentifier().equals(name)) type = imp.getName().getQualifier().map(Object::toString).orElse("");
            else continue;
            var t = Optional.ofNullable(type(type, n));
            var v = t.flatMap(td -> fieldOf(td, name, 0));
            if (v.isPresent()) return evalDeclaration(v.map(x -> (Node) x), n, depth).orElseThrow();
        }
        return Value.unresolved(name);
    }

    private Value evalFieldAccess(FieldAccessExpr f, int depth) {
        String name = f.getNameAsString();
        if (f.getScope() instanceof ThisExpr) {
            var t = f.findAncestor(TypeDeclaration.class);
            return t.flatMap(td -> fieldOf((TypeDeclaration<?>) td, name, 0))
                    .flatMap(v -> evalDeclaration(Optional.of(v), f, depth))
                    .orElseGet(() -> Value.unresolved(f.toString()));
        }
        return resolveType(f.getScope().toString(), f)
                .flatMap(t -> fieldOf(t, name, 0))
                .flatMap(v -> evalDeclaration(Optional.of(v), f, depth))
                .orElseGet(() -> Value.unresolved(f.toString()));
    }

    private Value evalCall(MethodCallExpr m, int depth) {
        String name = m.getNameAsString();
        // discoveryClient.getInstances("svc").get(0).getUri() / loadBalancer.choose("svc").getUri()
        for (Expression s = m; s instanceof MethodCallExpr c; s = c.getScope().orElse(null)) {
            if ((c.getNameAsString().equals("getInstances") || c.getNameAsString().equals("choose"))
                    && c.getArguments().size() == 1) {
                Value svc = eval(c.getArgument(0), depth + 1);
                return raw(Value.exact("lb://").concat(svc, m.toString()).withConfidence(Confidence.RESOLVED, null), m);
            }
        }
        // a zero-arg helper in the same class that only returns an expression: getBaseUrl() { return X; }
        if (m.getArguments().isEmpty() && (m.getScope().isEmpty() || m.getScope().get() instanceof ThisExpr)) {
            var helper = m.findAncestor(TypeDeclaration.class).stream()
                    .flatMap(t -> ((TypeDeclaration<?>) t).getMethodsByName(name).stream())
                    .filter(md -> md.getParameters().isEmpty() && md.getBody().map(b -> b.getStatements().size() == 1).orElse(false))
                    .flatMap(md -> md.getBody().get().getStatement(0).toReturnStmt().flatMap(r -> r.getExpression()).stream())
                    .findFirst();
            if (helper.isPresent()) return raw(eval(helper.get(), depth + 1).withConfidence(Confidence.RESOLVED, evidence(helper.get())), m);
        }
        if (m.getScope().isPresent() && m.getArguments().isEmpty()
                && List.of("toString", "trim", "strip", "toURI", "toUriString").contains(name)) {
            Value v = eval(m.getScope().get(), depth + 1);
            String text = !v.resolved() ? v.text() : name.equals("trim") ? v.text().trim() : name.equals("strip") ? v.text().strip() : v.text();
            return new Value(text, v.confidence(), m.toString(), v.via());
        }
        // Enum.CONST.getTopic(): the constant's constructor argument that the getter's field is assigned from
        if (m.getArguments().isEmpty() && name.length() > 3 && name.startsWith("get")
                && m.getScope().orElse(null) instanceof FieldAccessExpr f) {
            String field = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            var arg = resolveType(f.getScope().toString(), m)
                    .filter(EnumDeclaration.class::isInstance).map(EnumDeclaration.class::cast)
                    .flatMap(en -> en.getEntries().stream().filter(c -> c.getNameAsString().equals(f.getNameAsString())).findFirst()
                            .flatMap(c -> enumArg(en, c, field)));
            if (arg.isPresent()) return raw(eval(arg.get(), depth + 1).withConfidence(Confidence.RESOLVED, evidence(arg.get())), m);
        }
        if (m.getScope().map(Object::toString).orElse("").equals("String") && name.equals("valueOf")
                && m.getArguments().size() == 1) {
            return raw(eval(m.getArgument(0), depth + 1), m);
        }
        return Value.unresolved(m.toString());
    }

    private static Optional<Expression> enumArg(EnumDeclaration en, EnumConstantDeclaration c, String field) {
        int n = c.getArguments().size();
        for (ConstructorDeclaration ctor : en.getConstructors()) {
            if (ctor.getParameters().size() != n) continue;
            for (AssignExpr a : ctor.findAll(AssignExpr.class)) {
                var target = a.getTarget();
                String t = target instanceof FieldAccessExpr fa ? fa.getNameAsString() : target instanceof NameExpr ne ? ne.getNameAsString() : null;
                if (!field.equals(t) || !(a.getValue() instanceof NameExpr p)) continue;
                for (int i = 0; i < n; i++) {
                    if (ctor.getParameter(i).getNameAsString().equals(p.getNameAsString())) return Optional.of(c.getArgument(i));
                }
            }
        }
        if (!en.getConstructors().isEmpty()) return Optional.empty();
        // Lombok @AllArgsConstructor: arguments follow the instance fields in declaration order
        var fields = en.getFields().stream().filter(fd -> !fd.isStatic()).flatMap(fd -> fd.getVariables().stream())
                .map(VariableDeclarator::getNameAsString).toList();
        int i = fields.indexOf(field);
        return i >= 0 && i < n && fields.size() == n ? Optional.of(c.getArgument(i)) : Optional.empty();
    }
}
