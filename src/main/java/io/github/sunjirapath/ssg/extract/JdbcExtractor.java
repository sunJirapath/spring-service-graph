package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        for (CompilationUnit cu : s.units()) {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                boolean client = isClientCall(s, call);
                boolean wrapper = !client && isWrapperCall(s, call);
                if ((!client && !wrapper) || call.getArguments().isEmpty()) continue;
                if (client) {
                    sql(s, call, call.getArgument(0), s.eval(call.getArgument(0)), facts);
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

    private static boolean isClientCall(Service s, MethodCallExpr call) {
        return CLIENT_METHODS.contains(call.getNameAsString()) && call.getScope().isPresent()
                && Receivers.isReceiver(s, call.getScope().get(), CLIENT_TYPES, "jdbc");
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
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:from|join|into|update)\\s+([`\"\\[]?[A-Za-z_][\\w.$]*[`\"\\]]?)(?![\\w{])");
    private static final Pattern SYSTEM = Pattern.compile("(?i)^[`\"\\[]?(?:information_schema|pg_catalog|pg_)");
    private static final Set<String> NOT_TABLES = Set.of("select", "set", "values", "where", "lateral", "unnest", "dual",
            "only", "table", "the");

    /**
     * Table names in a SQL string, lower-cased, without schema or quotes, in order of appearance.
     * shortcut: implicit comma joins ("FROM a, b") yield only the first table; use explicit JOINs or extend this.
     */
    static List<String> tables(String sql) {
        String code = NOISE.matcher(sql).replaceAll(" ");
        var ctes = new LinkedHashSet<String>();
        Matcher c = CTE.matcher(code);
        while (c.find()) ctes.add(c.group(1).toLowerCase(Locale.ROOT));
        var out = new LinkedHashSet<String>();
        Matcher m = TABLE.matcher(code);
        boolean catalog = code.toLowerCase(Locale.ROOT).contains("information_schema");
        while (m.find()) {
            String t = DataExtractor.normalize(m.group(1));
            if (t.isEmpty() || NOT_TABLES.contains(t) || ctes.contains(t) || SYSTEM.matcher(m.group(1)).find()
                    || catalog && (t.equals("columns") || t.equals("tables"))) continue; // system catalog, not a table
            out.add(t);
        }
        return List.copyOf(out);
    }
}
