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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tables and collections: {@code @Entity}/{@code @Table}, {@code @Document}, Spring Data
 * repositories (via their entity type argument; derived query methods are not needed), and
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
        return facts;
    }

    private record Mapped(Value name, String store, String via) {}

    private static Optional<Mapped> table(Service s, TypeDeclaration<?> c) {
        if (Ann.has(c, "Entity")) {
            Value name = Ann.find(c, "Table").flatMap(a -> Ann.attr(a, "name", "value")).map(s::eval)
                    .orElse(Value.exact(snake(c.getNameAsString())));
            return Optional.of(new Mapped(normalized(name), "sql", "Entity"));
        }
        var doc = Ann.find(c, "Document");
        if (doc.isPresent()) {
            Value name = Ann.attr(doc.get(), "collection", "value").map(s::eval)
                    .orElse(Value.exact(Character.toLowerCase(c.getNameAsString().charAt(0)) + c.getNameAsString().substring(1)));
            return Optional.of(new Mapped(name, "mongo", "Document"));
        }
        return Optional.empty();
    }

    private static void repository(Service s, ClassOrInterfaceDeclaration c, List<Fact> facts) {
        if (Ann.has(c, "NoRepositoryBean")) return;
        var typeParameters = c.getTypeParameters().stream().map(p -> p.getNameAsString()).toList();
        for (ClassOrInterfaceType st : c.getExtendedTypes()) {
            if (st.getTypeArguments().map(a -> a.isEmpty()).orElse(true) || !isRepository(s, st, c, 0)) continue;
            var arg = st.getTypeArguments().get().get(0);
            String entity = arg instanceof ClassOrInterfaceType t ? t.getNameWithScope() : arg.asString();
            if (typeParameters.contains(entity)) continue; // a generic base like BaseRepo<T, ID>: its subtypes name the entity
            var mapped = s.index().resolveType(entity, c).flatMap(t -> table(s, t));
            if (mapped.isPresent()) {
                facts.add(new Fact.TableUse(false, mapped.get().name, mapped.get().store, s.evidence(c),
                        "repository " + c.getNameAsString()));
            } else {
                facts.add(new Fact.Problem("UNRESOLVED", "repository " + c.getNameAsString() + ": entity " + entity
                        + " not found in sources", s.evidence(c)));
            }
        }
    }

    /** A Spring Data *Repository, directly or through custom base interfaces found in sources. */
    private static boolean isRepository(Service s, ClassOrInterfaceType t, com.github.javaparser.ast.Node context, int depth) {
        if (t.getNameAsString().endsWith("Repository")) return true;
        if (depth > 64) return false; // only a guard against illegal cyclic extends in broken code
        return s.index().resolveType(t, context).filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(d -> ((ClassOrInterfaceDeclaration) d).getExtendedTypes().stream().anyMatch(x -> isRepository(s, x, d, depth + 1)))
                .orElse(false);
    }

    // ---------------------------------------------------------------- migrations

    private static void migrations(Service s, List<Fact> facts) {
        if (!Files.isDirectory(s.resources())) return;
        try (Stream<Path> walk = Files.walk(s.resources())) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                try { // one unreadable or malformed file must not hide the others
                    if (name.endsWith(".sql")) sql(s, f, Files.readString(f), facts);
                    else if (name.endsWith(".xml")) xml(s, f, Files.readString(f), facts);
                    else if (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json")) yaml(s, f, Files.readString(f), facts);
                } catch (IOException | RuntimeException e) {
                    System.err.println("warn: cannot read " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("warn: cannot list " + s.resources() + ": " + e.getMessage());
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

    private static void xml(Service s, Path f, String text, List<Fact> facts) {
        if (!text.contains("databaseChangeLog")) return;
        Matcher m = XML_CREATE_TABLE.matcher(text);
        while (m.find()) facts.add(own(s, f, text, m.start(), m.group(1), "liquibase"));
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
