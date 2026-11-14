package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.link.Linker;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DataLinkBranchGapTest {

    @TempDir Path tmp;

    @Test
    void mapsMongoRepositoryAndAssignsRootDdlToSqlEntityUse() {
        var repo = TempRepo.at(tmp.resolve("data"))
                .app()
                .java("x/Models.java", """
                        package x;
                        @Entity @Table(name = "orders") class Order {}
                        class Event {}
                        interface EventRepository extends MongoRepository<Event, Long> {}
                        interface OrderRepository extends CustomRepository<Order, Long> {}
                        """)
                .file("schema.sql", "CREATE TABLE orders (id bigint);\n")
                .file("other-schema.sql", "CREATE TABLE orders (id bigint);\n");

        var facts = repo.facts(new DataExtractor());

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .extracting(f -> {
                    var table = (Fact.TableUse) f;
                    return table.owns() + ":" + table.table().text() + ":" + table.store();
                })
                .contains("false:orders:sql", "true:orders:sql");
        // Event has no @Document, so only the MongoRepository fallback can produce this fact
        assertThat(facts).filteredOn(f -> f instanceof Fact.TableUse t && t.store().equals("mongo"))
                .extracting(f -> ((Fact.TableUse) f).table().text()).containsExactly("event");
        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .filteredOn(f -> ((Fact.TableUse) f).owns()).singleElement()
                .isInstanceOfSatisfying(Fact.TableUse.class, table -> assertThat(table.via()).isEqualTo("sql"));
    }

    @Test
    void readsLiquibaseYamlAndMapperWithoutNamespace() {
        var repo = TempRepo.at(tmp.resolve("resources"))
                .app()
                .java("x/Models.java", "package x; class Empty {}")
                .resource("db/changelog.yaml", """
                        databaseChangeLog:
                          - changeSet:
                              id: one
                              changes:
                                - createTable:
                                    tableName: yaml_orders
                        """)
                .resource("mapper/anonymous.xml", """
                        <mapper>
                          <select id="find">select * from xml_orders</select>
                        </mapper>
                        """)
                .resource("schema.sql", "CREATE TABLE resource_orders (id bigint);\n")
                .resource("changelog/ddl.sql", "CREATE TABLE changelog_orders (id bigint);\n");

        var facts = repo.facts(new DataExtractor());

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .extracting(f -> {
                    var table = (Fact.TableUse) f;
                    return table.owns() + ":" + table.table().text() + ":" + table.via();
                })
                .contains("true:yaml_orders:liquibase", "false:xml_orders:mybatis-xml",
                        "true:resource_orders:sql", "true:changelog_orders:liquibase");
    }

    @Test
    void distinguishesNativeAndJpqlQueriesAndSkipsCatalogTables() {
        var repo = TempRepo.at(tmp.resolve("queries"))
                .app()
                .java("x/Queries.java", """
                        package x;
                        @Entity @Table(name = "people") class Person {}
                        interface Queries {
                            @Query(value = "select * from information_schema.columns join real_table r on 1=1", nativeQuery = true)
                            Object nativeQuery();
                            @Query("select p from Person p") Object jpqlQuery();
                            @Query("select p from Person p join p.address a") Object joinPath();
                        }
                        """);

        var facts = repo.facts(new JdbcExtractor(Set.of()));

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .extracting(f -> ((Fact.TableUse) f).table().text())
                .containsExactly("real_table", "people", "people");
        assertThat(facts).filteredOn(f -> f instanceof Fact.TableUse t && t.via().equals("@Query"))
                .hasSize(3);
    }

    // FINDING: configured qualified SQL wrappers are ignored on object-creation receivers.
    @Test
    void readsQualifiedSqlWrappersAndReportsUnlistedSqlParameters() {
        var repo = TempRepo.at(tmp.resolve("wrappers"))
                .app()
                .java("x/Queries.java", """
                        package x;
                        class Db { static void exec(String sql) {} }
                        class Queries {
                            JdbcTemplate jdbc;
                            void run(String sql) {
                                Db.exec("UPDATE wrapped_orders SET n = 1");
                                new Db().exec("UPDATE skipped_orders SET n = 1");
                                jdbc.query(sql);
                            }
                        }
                        """);

        var facts = repo.facts(new JdbcExtractor(Set.of("Db.exec")));

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .extracting(f -> ((Fact.TableUse) f).table().text())
                .containsExactly("wrapped_orders");
        assertThat(facts).filteredOn(Fact.Problem.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Fact.Problem.class, problem -> {
                    assertThat(problem.kind()).isEqualTo("UNRESOLVED");
                    assertThat(problem.message()).contains("parameter 'sql'", "Queries.run", "--sql-methods");
                });
    }

    @Test
    void reachesJdbcSinkThroughInterfaceDispatchAndRecursiveHelper() {
        var repo = TempRepo.at(tmp.resolve("reach"))
                .app()
                .java("x/Api.java", """
                        package x;
                        @RestController class Api {
                            Worker worker;
                            @GetMapping("/work") Object run() { worker.work(); return null; }
                        }
                        interface Worker { void work(); }
                        @Service class WorkerImpl implements Worker {
                            JdbcTemplate jdbc;
                            RestTemplate rest;
                            public void work() {
                                this.jdbc.query("select * from reached_orders");
                                rest.getForObject("https://api.example/orders", Object.class);
                                helper();
                            }
                            void helper() { work(); }
                        }
                        """);
        var service = repo.service();
        var facts = Extractor.all().stream().flatMap(extractor -> extractor.extract(service).stream()).toList();
        var sink = facts.stream().filter(f -> f instanceof Fact.TableUse table && table.via().equals("jdbc"))
                .map(Fact.TableUse.class::cast).findFirst().orElseThrow();
        var call = facts.stream().filter(Fact.Call.class::isInstance).map(Fact.Call.class::cast).findFirst().orElseThrow();

        var reached = ReachAnalysis.analyze(service, facts).stream().map(Fact.Reach.class::cast).toList();

        assertThat(sink.table().text()).isEqualTo("reached_orders");
        assertThat(call.url().text()).isEqualTo("https://api.example/orders");
        assertThat(reached).hasSize(2).allSatisfy(r -> assertThat(r.path()).isEqualTo("/work"));
        assertThat(reached).extracting(Fact.Reach::sink).contains(sink.evidence(), call.evidence());
    }

    @Test
    void demotesSharedDdlOutsideOneModuleAndReportsTablesWithNoUniqueOwner() {
        var ddl = new Fact.TableUse(true, Value.exact("orders"), "sql", ev("repo", "service-a/schema.sql"), "sql");
        var a = new Linker.ServiceFacts("a", "repo", "service-a", List.of(ddl));
        var b = new Linker.ServiceFacts("b", "repo", "service-b", List.of(ddl));
        var demoted = Linker.link(List.of(a, b), Map.of(), List.of());

        assertThat(demoted.edges()).filteredOn(e -> e.kind() == Edge.Kind.OWNS || e.kind() == Edge.Kind.USES)
                .extracting(e -> e.kind() + ":" + e.from())
                .containsExactlyInAnyOrder("OWNS:service:a", "USES:service:b");

        var outside = new Fact.TableUse(true, Value.exact("shared_table"), "sql", ev("repo", "common/schema.sql"), "sql");
        var shared = Linker.link(List.of(
                new Linker.ServiceFacts("a", "repo", "service-a", List.of(outside)),
                new Linker.ServiceFacts("b", "repo", "service-b", List.of(outside))), Map.of(), List.of());

        assertThat(shared.findings()).filteredOn(f -> f.kind() == Finding.Kind.SHARED_TABLE).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("shared_table", "owned by 2 services: a, b"));
    }

    private static io.github.sunjirapath.ssg.model.Evidence ev(String repo, String file) {
        return new io.github.sunjirapath.ssg.model.Evidence(repo, file, 1);
    }
}
