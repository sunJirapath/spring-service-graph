package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tables used through plain JDBC: the SQL string passed to JdbcTemplate, NamedParameterJdbcTemplate,
 * JdbcOperations or JdbcClient, or to an in-house wrapper method named in {@code --sql-methods}
 * ({@code method} or {@code Type.method}). The SQL is folded like any other value (constants,
 * concatenation, text blocks) and table names are read after FROM, JOIN, INTO and UPDATE.
 */
public final class JdbcExtractor implements Extractor {

    private static final Set<String> CLIENT_TYPES = Set.of("JdbcTemplate", "JdbcOperations", "NamedParameterJdbcTemplate",
            "NamedParameterJdbcOperations", "JdbcClient");
    private static final Set<String> CLIENT_METHODS = Set.of("query", "queryForObject", "queryForList", "queryForMap",
            "queryForRowSet", "queryForStream", "update", "batchUpdate", "execute", "sql");
    private static final Pattern SQL_START = Pattern.compile("(?is)^\\s*(select|insert|update|delete|merge|with|upsert|replace)\\b");

    private final Set<String> sqlMethods;

    /** @param sqlMethods wrapper methods whose arguments carry SQL: {@code method} or {@code Type.method} */
    public JdbcExtractor(Set<String> sqlMethods) {
        this.sqlMethods = Set.copyOf(sqlMethods);
    }

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        var entities = DataExtractor.entityTables(s);
        for (CompilationUnit cu : s.units()) {
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) query(s, m, entities, facts);
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                boolean client = isClientCall(s, call);
                boolean wrapper = !client && isWrapperCall(s, call);
                if ((!client && !wrapper) || call.getArguments().isEmpty()) continue;
                if (client) {
                    Expression arg = call.getArgument(0);
                    if (call.getNameAsString().equals("execute") && isConnectionCallback(s, arg)) continue;
                    sql(s, call, arg, s.eval(arg), facts);
                    continue;
                }
                // a wrapper's SQL is whichever argument looks like SQL; the others are bind values
                var values = call.getArguments().stream().map(s::eval).toList();
                boolean any = false;
                for (int i = 0; i < values.size(); i++) {
                    if (SQL_START.matcher(values.get(i).text()).find()) {
                        sql(s, call, call.getArgument(i), values.get(i), facts);
                        any = true;
                    }
                }
                if (!any && values.stream().anyMatch(v -> !v.resolved())) {
                    facts.add(new Fact.Problem("UNRESOLVED", "SQL passed to " + call.getNameAsString()
                            + " not resolved statically (from `" + call + "`)", s.evidence(call)));
                }
            }
        }
        return facts;
    }

    private void sql(Service s, MethodCallExpr call, Expression arg, Value sql, List<Fact> facts) {
        var tables = tables(sql.text());
        for (String t : tables) {
            facts.add(new Fact.TableUse(false, new Value(t, sql.confidence(), sql.raw(), sql.via()), "sql", s.evidence(call), "jdbc"));
        }
        if (!tables.isEmpty() || sql.resolved()) return;
        var param = arg instanceof NameExpr n ? s.index().declarationOf(n.getNameAsString(), n).filter(Parameter.class::isInstance) : java.util.Optional.empty();
        if (param.isPresent()) {
            String owner = enclosing(call);
            if (owner != null && listed(owner)) return; // a configured wrapper passing its SQL through: its callers are read instead
            facts.add(new Fact.Problem("UNRESOLVED", "SQL arrives as parameter '" + arg + "' of " + owner
                    + "; list it in --sql-methods so the SQL at its call sites is read", s.evidence(call)));
        } else {
            facts.add(new Fact.Problem("UNRESOLVED", "SQL not resolved statically (from `" + sql.raw() + "`)", s.evidence(call)));
        }
    }

    /**
     * Spring Data {@code @Query}: native SQL names tables directly; JPQL names entities, mapped to their tables.
     * shortcut: JPQL entities are matched by class name (not {@code @Entity(name=)}) among the service's own units, upgrade if fleets rename entities
     */
    private static void query(Service s, MethodDeclaration m, Map<String, String> entities, List<Fact> facts) {
        var q = Ann.find(m, "Query");
        if (q.isEmpty()) return;
        boolean nativeQuery = Ann.attr(q.get(), "nativeQuery").map(e -> e.toString().equals("true")).orElse(false);
        var sqlExpr = Ann.attr(q.get(), "value");
        if (sqlExpr.isEmpty()) return;
        Value sql = s.eval(sqlExpr.get());
        var tables = nativeQuery ? tables(sql.text())
                : tables(sql.text(), raw -> raw.contains(".") ? null : entities.get(raw.toLowerCase(Locale.ROOT))); // "o.pets" is a join path
        for (String t : tables) {
            facts.add(new Fact.TableUse(false, new Value(t, sql.confidence(), sql.raw(), sql.via()), "sql", s.evidence(m), "@Query"));
        }
    }

    private static boolean isClientCall(Service s, MethodCallExpr call) {
        return CLIENT_METHODS.contains(call.getNameAsString()) && call.getScope().isPresent()
                && Receivers.isReceiver(s, call.getScope().get(), CLIENT_TYPES, "jdbc");
    }

    private static boolean isConnectionCallback(Service s, Expression arg) {
        return switch (arg) {
            case CastExpr c -> JavaIndex.simpleName(c.getType()).equals("ConnectionCallback");
            case EnclosedExpr e -> isConnectionCallback(s, e.getInner());
            case NameExpr n -> s.index().declaredType(n.getNameAsString(), n).filter("ConnectionCallback"::equals).isPresent();
            default -> false;
        };
    }

    private boolean isWrapperCall(Service s, MethodCallExpr call) {
        if (sqlMethods.isEmpty()) return false;
        String name = call.getNameAsString();
        if (sqlMethods.contains(name)) return true;
        String type = call.getScope().map(scope -> switch (scope) {
            case NameExpr n -> s.index().declaredType(n.getNameAsString(), n).orElse(n.getNameAsString()); // field or Type.m()
            case ThisExpr t -> enclosingType(call);
            default -> null;
        }).orElseGet(() -> enclosingType(call));
        return type != null && sqlMethods.contains(type + "." + name);
    }

    private boolean listed(String typeDotMethod) {
        return sqlMethods.contains(typeDotMethod) || sqlMethods.contains(typeDotMethod.substring(typeDotMethod.indexOf('.') + 1));
    }

    private static String enclosingType(MethodCallExpr call) {
        return call.findAncestor(TypeDeclaration.class).map(t -> ((TypeDeclaration<?>) t).getNameAsString()).orElse(null);
    }

    private static String enclosing(MethodCallExpr call) {
        var m = call.findAncestor(MethodDeclaration.class);
        String type = enclosingType(call);
        return m.isEmpty() || type == null ? null : type + "." + m.get().getNameAsString();
    }

    // ---------------------------------------------------------------- SQL

    private static final Pattern NOISE = Pattern.compile("(?is)--[^\\n]*|/\\*.*?\\*/|'(?:[^']|'')*'"
            + "|\\b(?:extract|trim|substring|position|overlay)\\s*\\([^()]*\\)"
            + "|\\bfor\\s+update\\b|\\bon\\s+duplicate\\s+key\\s+update\\b|\\bdo\\s+update\\b");
    private static final Pattern CTE = Pattern.compile("(?i)(?:\\bwith\\s+(?:recursive\\s+)?|,\\s*)([A-Za-z_]\\w*)\\s+as\\s*\\(");
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(from|join|into|update)\\s+([`\"\\[]?[A-Za-z_][\\w.$]*[`\"\\]]?)(?![\\w{])");
    /** The next table of an implicit comma join, {@code FROM a x, b y}: the previous table's optional alias, then a comma. */
    private static final Pattern COMMA = Pattern.compile("(?i)\\s*(?:(?:as\\s+)?(?!(?:where|join|inner|left|right|full|cross|natural|on|using|set|select|values|group|order|having|limit|union)\\b)[A-Za-z_]\\w*)?"
            + "\\s*,\\s*([`\"\\[]?[A-Za-z_][\\w.$]*[`\"\\]]?)(?![\\w{])");
    private static final Pattern SYSTEM = Pattern.compile("(?i)^[`\"\\[]?(?:information_schema|pg_catalog|pg_)");
    private static final Set<String> NOT_TABLES = Set.of("select", "set", "values", "where", "lateral", "unnest", "dual",
            "only", "table", "the");

    /** Table names in a SQL string, lower-cased, without schema or quotes, in order of appearance. */
    static List<String> tables(String sql) {
        return tables(sql, DataExtractor::normalize);
    }

    /** As {@link #tables(String)}, with {@code name} turning a raw table token into the table, or null to skip it. */
    static List<String> tables(String sql, UnaryOperator<String> name) {
        String code = NOISE.matcher(sql).replaceAll(" ");
        var ctes = new LinkedHashSet<String>();
        Matcher c = CTE.matcher(code);
        while (c.find()) ctes.add(c.group(1).toLowerCase(Locale.ROOT));
        var out = new LinkedHashSet<String>();
        Matcher m = TABLE.matcher(code);
        boolean catalog = code.toLowerCase(Locale.ROOT).contains("information_schema");
        while (m.find()) {
            String raw = m.group(2);
            boolean list = !m.group(1).equalsIgnoreCase("into") && !m.group(1).equalsIgnoreCase("update"); // INTO t a, b is a column list once MyBatis tags are gone
            Matcher more = COMMA.matcher(code);
            for (int end = m.end(); ; end = more.end()) {
                String t = name.apply(raw);
                if (!(t == null || t.isEmpty() || NOT_TABLES.contains(t) || ctes.contains(t) || SYSTEM.matcher(raw).find()
                        || catalog && (t.equals("columns") || t.equals("tables")))) out.add(t); // else system catalog, not a table
                more.region(end, code.length());
                if (!list || !more.lookingAt()) break;
                raw = more.group(1);
            }
        }
        return List.copyOf(out);
    }
}
