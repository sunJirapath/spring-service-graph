package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcExtractorTest {

    @Test
    void extractsEveryJdbcTemplateClientMethod(@TempDir Path tmp) {
        var facts = extract(tmp, "methods", """
                package x;
                class Queries {
                    JdbcTemplate jdbc;
                    void run() {
                        jdbc.query("SELECT * FROM t_query");
                        jdbc.queryForObject("SELECT * FROM t_object");
                        jdbc.queryForList("SELECT * FROM t_list");
                        jdbc.queryForMap("SELECT * FROM t_map");
                        jdbc.queryForRowSet("SELECT * FROM t_row_set");
                        jdbc.queryForStream("SELECT * FROM t_stream");
                        jdbc.update("UPDATE t_update SET n = 1");
                        jdbc.batchUpdate("UPDATE t_batch SET n = 1");
                        jdbc.execute("DELETE FROM t_execute");
                    }
                }
                """);

        assertThat(tableNames(facts)).containsExactly(
                "t_query", "t_object", "t_list", "t_map", "t_row_set", "t_stream", "t_update", "t_batch", "t_execute");
    }

    @Test
    void extractsAllSupportedJdbcReceiverTypes(@TempDir Path tmp) {
        var facts = extract(tmp, "types", """
                package x;
                class Queries {
                    JdbcTemplate template;
                    JdbcOperations operations;
                    NamedParameterJdbcTemplate namedTemplate;
                    NamedParameterJdbcOperations namedOperations;
                    void run() {
                        template.query("SELECT * FROM template_table");
                        operations.query("SELECT * FROM operations_table");
                        namedTemplate.query("SELECT * FROM named_template_table");
                        namedOperations.query("SELECT * FROM named_operations_table");
                    }
                }
                """);

        assertThat(tableNames(facts)).containsExactly(
                "template_table", "operations_table", "named_template_table", "named_operations_table");
    }

    @Test
    void jdbcClientSqlChainProducesOneUsePerTable(@TempDir Path tmp) {
        var facts = extract(tmp, "client", """
                package x;
                class Queries {
                    JdbcClient client;
                    void run() {
                        client.sql("SELECT * FROM orders JOIN order_lines ON order_lines.order_id = orders.id").query();
                    }
                }
                """);

        assertThat(facts).hasSize(2).allSatisfy(f -> assertJdbcUse(f, 5));
        assertThat(tableNames(facts)).containsExactly("orders", "order_lines");
    }

    @Test
    void tableUseCarriesJdbcMetadataAndCallSiteEvidence(@TempDir Path tmp) {
        var facts = extract(tmp, "metadata", """
                package x;
                class Queries {
                    JdbcTemplate jdbc;
                    void run() {
                        jdbc.query("SELECT * FROM orders");
                    }
                }
                """);

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TableUse.class, t -> {
            assertThat(t.owns()).isFalse();
            assertThat(t.store()).isEqualTo("sql");
            assertThat(t.via()).isEqualTo("jdbc");
            assertThat(t.evidence().file()).endsWith("Queries.java");
            assertThat(t.evidence().line()).isEqualTo(5);
        });
    }

    @Test
    void foldsStaticFinalSqlFromAnotherClassAsResolved(@TempDir Path tmp) {
        var facts = extract(tmp, "constant", """
                package x;
                class Sql { static final String FIND = "SELECT * FROM Orders"; }
                class Queries { JdbcTemplate jdbc; void run() { jdbc.query(Sql.FIND); } }
                """);

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TableUse.class, t -> {
            assertThat(t.table().text()).isEqualTo("orders");
            assertThat(t.table().confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(t.table().via()).singleElement().satisfies(e -> assertThat(e.file()).endsWith("Queries.java"));
        });
    }

    @Test
    void extractsSqlFromJavaTextBlock(@TempDir Path tmp) {
        var facts = extract(tmp, "text-block", """
                package x;
                class Queries {
                    JdbcTemplate jdbc;
                    void run() {
                        jdbc.query(\"""
                                SELECT *
                                FROM audit_events
                                \""");
                    }
                }
                """);

        assertThat(tableNames(facts)).containsExactly("audit_events");
    }

    @Test
    void foldsConcatenatedConstants(@TempDir Path tmp) {
        var facts = extract(tmp, "concatenated", """
                package x;
                class Sql {
                    static final String PREFIX = "SELECT * FROM ";
                    static final String TABLE = "ledger_entries";
                }
                class Queries { JdbcTemplate jdbc; void run() { jdbc.query(Sql.PREFIX + Sql.TABLE); } }
                """);

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TableUse.class, t -> {
            assertThat(t.table().text()).isEqualTo("ledger_entries");
            assertThat(t.table().confidence()).isEqualTo(Confidence.RESOLVED);
        });
    }

    @Test
    void foldsSqlFromLocalVariable(@TempDir Path tmp) {
        var facts = extract(tmp, "local", """
                package x;
                class Queries {
                    JdbcTemplate jdbc;
                    void run() {
                        String sql = "SELECT * FROM local_table";
                        jdbc.query(sql);
                    }
                }
                """);

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TableUse.class,
                t -> assertThat(t.table().confidence()).isEqualTo(Confidence.RESOLVED));
        assertThat(tableNames(facts)).containsExactly("local_table");
    }

    @Test
    void ignoresSameNamedMethodsOnUnrelatedDeclaredTypes(@TempDir Path tmp) {
        var facts = extract(tmp, "unrelated", """
                package x;
                class Queries {
                    Map map;
                    List list;
                    OrderRepository repository;
                    void run() {
                        map.query("SELECT * FROM map_table");
                        list.update("UPDATE list_table SET n = 1");
                        repository.update("DELETE FROM repository_table");
                    }
                }
                """);

        assertThat(facts).isEmpty();
    }

    @Test
    void acceptsUndeclaredReceiverWhoseNameContainsJdbc(@TempDir Path tmp) {
        var facts = extract(tmp, "receiver-hint", """
                package x;
                class Queries {
                    void run() {
                        legacyJdbc.query("SELECT * FROM hinted_table");
                    }
                }
                """);

        assertThat(tableNames(facts)).containsExactly("hinted_table");
    }

    @Test
    void runtimeBuiltSqlProducesOneUnresolvedProblem(@TempDir Path tmp) {
        var facts = extract(tmp, "dynamic", """
                package x;
                class Queries {
                    JdbcTemplate jdbc;
                    void run() { jdbc.query(buildSql()); }
                    String buildSql() { return System.getenv("SQL"); }
                }
                """);

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.Problem.class, p -> {
            assertThat(p.kind()).isEqualTo("UNRESOLVED");
            assertThat(p.message()).contains("not resolved");
        });
    }

    @Test
    void sqlMethodParameterProblemNamesOwnerAndSuggestsOption(@TempDir Path tmp) {
        var facts = extract(tmp, "parameter", """
                package x;
                class Dao {
                    JdbcTemplate jdbc;
                    void run(String sql) { jdbc.query(sql); }
                }
                """);

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.Problem.class, p -> {
            assertThat(p.kind()).isEqualTo("UNRESOLVED");
            assertThat(p.message()).contains("parameter 'sql'", "Dao.run", "--sql-methods");
        });
    }

    @Test
    void configuredWrapperParameterProducesNoPassThroughProblem(@TempDir Path tmp) {
        String source = """
                package x;
                class Dao {
                    JdbcTemplate jdbc;
                    void run(String sql) { jdbc.query(sql); }
                }
                """;

        assertThat(extract(tmp, "qualified-wrapper", source, Set.of("Dao.run"))).isEmpty();
        assertThat(extract(tmp, "bare-wrapper", source, Set.of("run"))).isEmpty();
    }

    @Test
    void bareWrapperNameMatchesAnyReceiver(@TempDir Path tmp) {
        var facts = extract(tmp, "bare-name", """
                package x;
                class Queries {
                    Object arbitrary;
                    void run() { arbitrary.exec("SELECT * FROM bare_wrapper_table"); }
                }
                """, Set.of("exec"));

        assertThat(tableNames(facts)).containsExactly("bare_wrapper_table");
    }

    @Test
    void qualifiedWrapperMatchesFieldStaticUnscopedAndThisCalls(@TempDir Path tmp) {
        var facts = extract(tmp, "qualified-calls", """
                package x;
                class Db {
                    void invoke() {
                        exec("SELECT * FROM unscoped_table");
                        this.exec("SELECT * FROM this_table");
                    }
                    static void exec(String sql) {}
                }
                class Queries {
                    Db db;
                    void run() {
                        db.exec("SELECT * FROM field_table");
                        Db.exec("SELECT * FROM static_table");
                    }
                }
                """, Set.of("Db.exec"));

        assertThat(tableNames(facts)).containsExactly("unscoped_table", "this_table", "field_table", "static_table");
    }

    @Test
    void qualifiedWrapperDoesNotMatchDifferentDeclaredType(@TempDir Path tmp) {
        var facts = extract(tmp, "qualified-negative", """
                package x;
                class Db { static void exec(String sql) {} }
                class Other { void exec(String sql) {} }
                class Queries {
                    Other other;
                    void run() { other.exec("SELECT * FROM wrong_table"); }
                }
                """, Set.of("Db.exec"));

        assertThat(facts).isEmpty();
    }

    @Test
    void wrapperUsesSqlLookingArgumentAndIgnoresUnresolvedBindArguments(@TempDir Path tmp) {
        var facts = extract(tmp, "wrapper-args", """
                package x;
                class Db { void exec(Object bind, String sql, Object another) {} }
                class Queries {
                    Db db;
                    void run() { db.exec(runtimeBind(), "WITH x AS (SELECT * FROM source_table) SELECT * FROM x", anotherBind()); }
                    Object runtimeBind() { return new Object(); }
                    Object anotherBind() { return new Object(); }
                }
                """, Set.of("Db.exec"));

        assertThat(facts).singleElement().isInstanceOf(Fact.TableUse.class);
        assertThat(tableNames(facts)).containsExactly("source_table");
    }

    @Test
    void wrapperWithNoSqlAndAnUnresolvedArgumentProducesOneProblem(@TempDir Path tmp) {
        var facts = extract(tmp, "wrapper-unresolved", """
                package x;
                class Db { void exec(Object value, int count) {} }
                class Queries {
                    Db db;
                    void run() { db.exec(runtimeValue(), 1); }
                    Object runtimeValue() { return new Object(); }
                }
                """, Set.of("Db.exec"));

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.Problem.class, p -> {
            assertThat(p.kind()).isEqualTo("UNRESOLVED");
            assertThat(p.message()).contains("SQL passed to exec", "not resolved");
        });
    }

    @Test
    void resolvedSqlWithoutTablesProducesNeitherUseNorProblem(@TempDir Path tmp) {
        var facts = extract(tmp, "no-table", """
                package x;
                class Queries { JdbcTemplate jdbc; void run() { jdbc.query("SELECT 1"); } }
                """);

        assertThat(facts).isEmpty();
    }

    @Test
    void clientCallWithoutArgumentsIsIgnored(@TempDir Path tmp) {
        var facts = extract(tmp, "no-args", """
                package x;
                class Queries { JdbcTemplate jdbc; void run() { jdbc.query(); } }
                """);

        assertThat(facts).isEmpty();
    }

    private static List<Fact> extract(Path tmp, String name, String source) {
        return extract(tmp, name, source, Set.of());
    }

    private static List<Fact> extract(Path tmp, String name, String source, Set<String> sqlMethods) {
        return TempRepo.at(tmp.resolve(name)).app().java("x/Queries.java", source)
                .facts(new JdbcExtractor(sqlMethods));
    }

    private static List<String> tableNames(List<Fact> facts) {
        return facts.stream().filter(Fact.TableUse.class::isInstance).map(Fact.TableUse.class::cast)
                .map(t -> t.table().text()).toList();
    }

    private static void assertJdbcUse(Fact fact, int line) {
        assertThat(fact).isInstanceOfSatisfying(Fact.TableUse.class, t -> {
            assertThat(t.owns()).isFalse();
            assertThat(t.store()).isEqualTo("sql");
            assertThat(t.via()).isEqualTo("jdbc");
            assertThat(t.evidence().line()).isEqualTo(line);
        });
    }
}
