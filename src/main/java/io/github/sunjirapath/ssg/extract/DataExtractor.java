package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.Config;
import io.github.sunjirapath.ssg.scan.Service;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tables and collections: {@code @Entity}/{@code @Table}, MyBatis-Plus {@code @TableName}, {@code @Document},
 * Spring Data repositories and MyBatis-Plus {@code BaseMapper}s (via their entity type argument;
 * derived query methods are not needed), and
 * ownership from migrations: Liquibase {@code createTable}, Flyway and {@code schema.sql}.
 */
public final class DataExtractor implements Extractor {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)\\bcreate\\s+(?:cached\\s+|memory\\s+|temporary\\s+|temp\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?([`\"\\[\\]\\w.]+)");
    private static final Pattern XML_CREATE_TABLE = Pattern.compile("<createTable\\b[^>]*?\\btableName\\s*=\\s*\"([^\"]+)\"");

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        for (CompilationUnit cu : s.units()) {
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                table(s, c).ifPresent(t -> facts.add(new Fact.TableUse(false, t.name, t.store, s.evidence(c), "@" + t.via)));
                if (c.isInterface()) repository(s, c, facts);
            }
        }
        migrations(s, facts);
        rootDdl(s, facts);
        return facts;
    }

    private record Ddl(Path file, String table, int line, String via) {}

    private static final Map<Path, List<Ddl>> ROOT_DDL = new ConcurrentHashMap<>(); // parsed once per repo, shared by all services

    /** Root .sql files outside src/ and test fixtures: owned by services that use the table. */
    private static void rootDdl(Service s, List<Fact> facts) {
        var used = new HashSet<String>();
        var owned = new HashSet<String>();
        for (Fact f : facts)
            if (f instanceof Fact.TableUse t && t.table().resolved() && t.store().equals("sql")) (t.owns() ? owned : used).add(t.table().text());
        if (used.isEmpty()) return;
        for (Ddl d : ROOT_DDL.computeIfAbsent(s.repo().root(), r -> rootDdl(s))) {
            if (used.contains(d.table) && owned.add(d.table))
                facts.add(new Fact.TableUse(true, Value.exact(d.table), "sql", s.repo().evidence(d.file, d.line), d.via));
        }
    }

    private static List<Ddl> rootDdl(Service s) {
        var out = new ArrayList<Ddl>();
        try {
            for (Path f : s.repo().files(s.repo().root())) {
                if (!f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")) continue;
                if (("/" + s.repo().relative(f)).contains("/src/")) continue; // module resources are read by migrations()
                if (("/" + s.repo().relative(f)).matches("(?i).*/tests?/.*")) continue;
                try {
                    String code = blankComments(Files.readString(f));
                    Matcher m = CREATE_TABLE.matcher(code);
                    while (m.find()) {
                        int line = (int) code.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
                        out.add(new Ddl(f, normalize(m.group(1)), line, via(s, f)));
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("warn: cannot read " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("warn: cannot list " + s.repo().root() + ": " + e.getMessage());
        }
        return out;
    }

    private record Mapped(Value name, String store, String via) {}

    private static Optional<Mapped> table(Service s, TypeDeclaration<?> c) {
        if (Ann.has(c, "Entity")) {
            Value name = Ann.find(c, "Table").flatMap(a -> Ann.attr(a, "name", "value")).map(s::eval)
                    .orElse(Value.exact(snake(c.getNameAsString())));
            return Optional.of(new Mapped(normalized(name), "sql", "Entity"));
        }
        var tableName = Ann.find(c, "TableName"); // MyBatis-Plus; its default naming is snake case too
        if (tableName.isPresent()) {
            Value name = Ann.attr(tableName.get(), "value").map(s::eval).orElse(Value.exact(snake(c.getNameAsString())));
            return Optional.of(new Mapped(normalized(name), "sql", "TableName"));
        }
        var table = Ann.find(c, "Table").flatMap(a -> Ann.attr(a, "name", "value")); // tk.mybatis entities carry @Table alone
        if (table.isPresent()) return Optional.of(new Mapped(normalized(s.eval(table.get())), "sql", "Table"));
        var doc = Ann.find(c, "Document");
        if (doc.isPresent()) {
            boolean elastic = elastic(c);
            Value name = Ann.attr(doc.get(), elastic ? new String[]{"indexName"} : new String[]{"collection", "value"}).map(s::eval)
                    .orElse(Value.exact(collection(c)));
            return Optional.of(new Mapped(name, elastic ? "elasticsearch" : "mongo", "Document"));
        }
        return Optional.empty();
    }

    private static String collection(TypeDeclaration<?> c) {
        String name = c.getNameAsString();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Lower-cased entity class name to table, for resolving JPQL entity names. */
    static Map<String, String> entityTables(Service s) {
        var out = new HashMap<String, String>();
        for (CompilationUnit cu : s.units())
            for (TypeDeclaration<?> c : cu.findAll(TypeDeclaration.class))
                if (Ann.has(c, "Entity"))
                    table(s, c).filter(t -> t.name.resolved()).ifPresent(t -> out.put(c.getNameAsString().toLowerCase(Locale.ROOT), t.name.text()));
        return out;
    }

    private static boolean elastic(TypeDeclaration<?> c) {
        return c.findCompilationUnit().map(cu -> cu.getImports().stream()
                .anyMatch(i -> i.getNameAsString().startsWith("org.springframework.data.elasticsearch"))).orElse(false);
    }

    private static void repository(Service s, ClassOrInterfaceDeclaration c, List<Fact> facts) {
        if (Ann.has(c, "NoRepositoryBean")) return;
        var typeParameters = c.getTypeParameters().stream().map(p -> p.getNameAsString()).toList();
        for (ClassOrInterfaceType st : c.getExtendedTypes()) {
            var base = repositoryBase(s, st, c, Map.of(), 0);
            if (base.isEmpty()) continue;
            String entity = base.get().entity();
            if (typeParameters.contains(entity)) continue; // a generic base like BaseRepo<T, ID>: its subtypes name the entity
            var type = s.index().resolveType(entity, c);
            var mapped = type.flatMap(t -> table(s, t));
            if (mapped.isEmpty() && type.isPresent() && base.get().name().endsWith("BaseMapper")) // MyBatis-Plus needs no @TableName
                mapped = Optional.of(new Mapped(Value.exact(snake(type.get().getNameAsString())), "sql", "BaseMapper"));
            if (mapped.isEmpty() && type.isPresent() && Set.of("MongoRepository", "ReactiveMongoRepository").contains(base.get().name()))
                mapped = Optional.of(new Mapped(Value.exact(collection(type.get())), "mongo", base.get().name()));
            if (mapped.isPresent()) {
                facts.add(new Fact.TableUse(false, mapped.get().name, mapped.get().store, s.evidence(c),
                        "repository " + c.getNameAsString()));
            } else {
                facts.add(new Fact.Problem("UNRESOLVED", "repository " + c.getNameAsString() + ": entity " + entity
                        + " not found in sources", s.evidence(c)));
            }
        }
    }

    /**
     * A Spring Data *Repository or a MyBatis-Plus BaseMapper (MPJBaseMapper from mybatis-plus-join), directly or
     * through custom base interfaces found in sources, such as yudao's BaseMapperX. Returns that framework base's name.
     */
    private record RepositoryType(String name, String entity) {}

    private static Optional<RepositoryType> repositoryBase(Service s, ClassOrInterfaceType t,
            com.github.javaparser.ast.Node context, Map<String, String> arguments, int depth) {
        if (depth > 64) return Optional.empty(); // only a guard against illegal cyclic extends in broken code
        String name = t.getNameAsString();
        var args = t.getTypeArguments().stream().flatMap(List::stream).map(a -> {
            String value = a.asString();
            if (arguments.containsKey(value)) return arguments.get(value);
            return s.index().resolveType(value, context).flatMap(TypeDeclaration::getFullyQualifiedName).orElse(value);
        }).toList();
        var source = s.index().resolveType(t, context).filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast);
        if (!args.isEmpty() && (name.equals("BaseMapper") || name.equals("MPJBaseMapper")))
            return Optional.of(new RepositoryType(name, args.getFirst()));
        if (source.isPresent()) {
            var bindings = new HashMap<String, String>();
            var declaration = source.get();
            for (int i = 0; i < Math.min(args.size(), declaration.getTypeParameters().size()); i++)
                bindings.put(declaration.getTypeParameter(i).getNameAsString(), args.get(i));
            return declaration.getExtendedTypes().stream()
                    .flatMap(parent -> repositoryBase(s, parent, declaration, bindings, depth + 1).stream()).findFirst();
        }
        return !args.isEmpty() && (name.endsWith("Repository") || name.equals("BaseMapper") || name.equals("MPJBaseMapper"))
                ? Optional.of(new RepositoryType(name, args.getFirst())) : Optional.empty();
    }

    // ---------------------------------------------------------------- migrations

    /**
     * Resources of the service module and the library modules it depends on, plus mapper XML kept next to the Java
     * sources. A library mapper XML counts only when its namespace is a mapper interface among the service's units.
     */
    private static void migrations(Service s, List<Fact> facts) {
        Map<String, TypeDeclaration<?>> types = null; // FQNs declared in the service's units, for mapper XML
        for (Path module : s.modules()) {
            for (Path dir : List.of(module.resolve("src/main/resources"), module.resolve("src/main/java"))) {
                boolean javaDir = dir.endsWith("java");
                try {
                    for (Path f : s.repo().files(dir)) {
                        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (javaDir && !name.endsWith(".xml")) continue;
                        try { // one unreadable or malformed file must not hide the others
                            if (name.endsWith(".sql")) sql(s, f, Files.readString(f), facts);
                            else if (name.endsWith(".xml")) {
                                String text = Files.readString(f);
                                if (text.contains("<mapper")) {
                                    text = XML_COMMENT.matcher(text).replaceAll(m -> m.group().replaceAll("[^\\n]", " "));
                                    if (types == null) types = declaredTypes(s);
                                    if (!module.equals(s.moduleRoot()) && !types.containsKey(namespace(text))) continue; // a shared mapper this service never uses
                                }
                                xml(s, f, text, facts, types == null ? Map.of() : types);
                            } else if (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json")) yaml(s, f, Files.readString(f), facts);
                        } catch (IOException | RuntimeException e) {
                            System.err.println("warn: cannot read " + f + ": " + e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    System.err.println("warn: cannot list " + dir + ": " + e.getMessage());
                }
            }
        }
    }

    private static String via(Service s, Path f) {
        String p = "/" + s.repo().relative(f);
        return p.contains("/db/migration") || p.matches(".*/V\\d+[^/]*__[^/]*\\.sql") ? "flyway"
                : p.contains("changelog") ? "liquibase" : "sql";
    }

    private static void sql(Service s, Path f, String text, List<Fact> facts) {
        String code = blankComments(text);
        Matcher m = CREATE_TABLE.matcher(code);
        while (m.find()) facts.add(own(s, f, code, m.start(), m.group(1), via(s, f)));
    }

    private static final Pattern SQL_COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /** Replaces SQL comments with spaces, keeping newlines so offsets still map to the right lines. */
    static String blankComments(String sql) {
        Matcher m = SQL_COMMENT.matcher(sql);
        var sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replaceAll("[^\\n]", " ")));
        return m.appendTail(sb).toString();
    }

    private static void xml(Service s, Path f, String text, List<Fact> facts, Map<String, TypeDeclaration<?>> types) {
        if (text.contains("<mapper")) mapper(s, f, text, facts, types);
        if (!text.contains("databaseChangeLog")) return;
        Matcher m = XML_CREATE_TABLE.matcher(text);
        while (m.find()) facts.add(own(s, f, text, m.start(), m.group(1), "liquibase"));
    }

    private static final Pattern NAMESPACE = Pattern.compile("<mapper\\b[^>]*\\bnamespace\\s*=\\s*[\"']([^\"']+)");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern RESULT_MAP = Pattern.compile("<resultMap\\b[^>]*\\btype\\s*=\\s*[\"']([^\"']+)[\"']");

    private static String namespace(String mapperXml) {
        Matcher m = NAMESPACE.matcher(mapperXml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static Map<String, TypeDeclaration<?>> declaredTypes(Service s) {
        var out = new HashMap<String, TypeDeclaration<?>>();
        for (CompilationUnit cu : s.units())
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) t.getFullyQualifiedName().ifPresent(name -> out.putIfAbsent(name, t));
        return out;
    }

    private static final Pattern MAPPER_STATEMENT = Pattern.compile("<(select|insert|update|delete)\\b[^>]*>(.*?)</\\1>", Pattern.DOTALL);

    private static final Pattern SQL_FRAGMENT = Pattern.compile("<sql\\b[^>]*\\bid\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</sql>", Pattern.DOTALL);
    private static final Pattern INCLUDE = Pattern.compile("<include\\b[^>]*\\brefid\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?(?:/>|>.*?</include>)", Pattern.DOTALL);

    /**
     * MyBatis mapper XML: tables used by each statement, with {@code <include>} fragments of the same mapper folded in.
     * shortcut: fragments of other mappers ({@code other.ns.id}) are not folded in, upgrade when fleets share SQL fragments across mappers.
     */
    private static void mapper(Service s, Path f, String text, List<Fact> facts, Map<String, TypeDeclaration<?>> types) {
        var fragments = new HashMap<String, String>();
        String ns = namespace(text);
        if (types.get(ns) instanceof ClassOrInterfaceDeclaration mapper && mapper.isInterface()) {
            var modules = new HashSet<>(s.modules());
            Matcher mapped = RESULT_MAP.matcher(text);
            while (mapped.find()) {
                String name = mapped.group(1).trim();
                if (!name.contains(".")) continue; // shortcut: aliases need configured lookup when XML aliases are required
                s.index().resolveType(name, mapper).map(t -> s.index().preferring(t, modules))
                        .filter(t -> t.getFullyQualifiedName().filter(name::equals).isPresent())
                        .filter(t -> modules.contains(s.index().moduleOf(s.index().fileOf(t.findCompilationUnit().orElseThrow()))))
                        .ifPresent(t -> table(s, t).ifPresent(table -> facts.add(new Fact.TableUse(false,
                                table.name, table.store, s.evidence(t), "@" + table.via))));
            }
        }
        Matcher fm = SQL_FRAGMENT.matcher(text);
        while (fm.find()) {
            fragments.put(fm.group(1), fm.group(2));
            fragments.put(ns + "." + fm.group(1), fm.group(2));
        }
        Matcher m = MAPPER_STATEMENT.matcher(text);
        while (m.find()) {
            String sql = fold(m.group(2), fragments, new HashSet<>()).replaceAll("<!\\[CDATA\\[|\\]\\]>", " ").replaceAll("<[^>]*>", " ");
            int line = (int) text.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
            for (String t : JdbcExtractor.tables(sql))
                facts.add(new Fact.TableUse(false, Value.exact(t), "sql", s.repo().evidence(f, line), "mybatis-xml"));
        }
    }

    /** Replaces each {@code <include refid>} by its fragment; an include cycle or unknown fragment becomes blank. */
    private static String fold(String body, Map<String, String> fragments, Set<String> open) {
        Matcher m = INCLUDE.matcher(body);
        var sb = new StringBuilder();
        while (m.find()) {
            String id = m.group(1);
            String rep = " ";
            if (fragments.containsKey(id) && open.add(id)) {
                rep = fold(fragments.get(id), fragments, open);
                open.remove(id);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        return m.appendTail(sb).toString();
    }

    private static void yaml(Service s, Path f, String text, List<Fact> facts) {
        if (!text.contains("databaseChangeLog")) return;
        for (Node doc : Config.composeAll(new StringReader(text))) walk(s, f, doc, facts);
    }

    private static void walk(Service s, Path f, Node n, List<Fact> facts) {
        if (n instanceof SequenceNode seq) seq.getValue().forEach(c -> walk(s, f, c, facts));
        if (!(n instanceof MappingNode m)) return;
        for (NodeTuple t : m.getValue()) {
            if (t.getKeyNode() instanceof ScalarNode k && k.getValue().equals("createTable") && t.getValueNode() instanceof MappingNode ct) {
                for (NodeTuple a : ct.getValue()) {
                    if (a.getKeyNode() instanceof ScalarNode ak && ak.getValue().equals("tableName") && a.getValueNode() instanceof ScalarNode v) {
                        int line = v.getStartMark().getLine() + 1;
                        facts.add(new Fact.TableUse(true, Value.exact(normalize(v.getValue())), "sql", s.repo().evidence(f, line), "liquibase"));
                    }
                }
            }
            walk(s, f, t.getValueNode(), facts);
        }
    }

    private static Fact own(Service s, Path f, String text, int offset, String table, String via) {
        int line = (int) text.substring(0, offset).chars().filter(ch -> ch == '\n').count() + 1;
        return new Fact.TableUse(true, Value.exact(normalize(table)), "sql", s.repo().evidence(f, line), via);
    }

    /** Lower-case, no quotes, no schema prefix, so migrations and entities name the same table. */
    static String normalize(String table) {
        String t = table.replaceAll("[`\"\\[\\]]", "");
        return t.substring(t.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static Value normalized(Value v) {
        return v.resolved() ? new Value(normalize(v.text()), v.confidence(), v.raw(), v.via()) : v;
    }

    /** Spring Boot's default physical naming: AccountHolder -> account_holder. */
    static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
