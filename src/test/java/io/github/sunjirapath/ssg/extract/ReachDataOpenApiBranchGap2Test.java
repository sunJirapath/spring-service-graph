package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReachDataOpenApiBranchGap2Test {
    @TempDir Path tmp;

    @Test
    void interfaceDispatchMatchesNameAndArityAndDoesNotFanOutToWrongOverload() {
        var repo = TempRepo.at(tmp.resolve("reach")).app().java("x/Api.java", """
                package x;
                @RestController class Api {
                    Worker worker;
                    WorkerCycle cycle;
                    @GetMapping("/run") Object run() { worker.work(); cycle.touch(); return null; }
                }
                interface Worker { void work(); }
                interface WorkerCycle extends OtherCycle { void touch(); }
                interface OtherCycle extends WorkerCycle {}
                class CycleImpl implements WorkerCycle { public void touch() {} }
                @Service class WorkerImpl implements Worker {
                    JdbcTemplate jdbc;
                    public void work() { jdbc.query("select * from reached"); }
                    void work(String unused) { jdbc.query("select * from wrong_arity"); }
                }
                """);
        var service = repo.service();
        var facts = Extractor.all().stream().flatMap(x -> x.extract(service).stream()).toList();
        var reached = ReachAnalysis.analyze(service, facts);

        assertThat(reached).filteredOn(Fact.Reach.class::isInstance).extracting(f -> ((Fact.Reach) f).chain())
                .containsExactly("Api.run → WorkerImpl.work");
        assertThat(reached).filteredOn(Fact.Reach.class::isInstance).extracting(f -> ((Fact.Reach) f).path())
                .containsExactly("/run");
    }

    @Test
    void reachStopsFollowingCallsAtTheDepthLimit() {
        var repo = TempRepo.at(tmp.resolve("depth")).app().java("x/Api.java", """
                package x;
                @RestController class Api {
                    RestTemplate rest;
                    @GetMapping("/deep") Object run() { one(); return null; }
                    void one() { two(); }
                    void two() { three(); }
                    void three() { four(); }
                    void four() { five(); }
                    void five() { six(); }
                    void six() { seven(); }
                    void seven() { rest.getForObject("https://too-deep.example", Object.class); }
                }
                """);
        var service = repo.service();
        var facts = Extractor.all().stream().flatMap(x -> x.extract(service).stream()).toList();

        assertThat(ReachAnalysis.analyze(service, facts)).filteredOn(Fact.Reach.class::isInstance).isEmpty();
    }

    @Test
    void repeatedDispatchToTheSameHelperEmitsItsSinkOnce() {
        var repo = TempRepo.at(tmp.resolve("visited")).app().java("x/Api.java", """
                package x;
                @RestController class Api {
                    @GetMapping("/shared") Object run() { first(); second(); return null; }
                    void first() { shared(); }
                    void second() { shared(); }
                    JdbcTemplate jdbc;
                    void shared() { jdbc.query("select * from shared_table"); }
                }
                """);
        var service = repo.service();
        var facts = Extractor.all().stream().flatMap(x -> x.extract(service).stream()).toList();

        assertThat(ReachAnalysis.analyze(service, facts)).filteredOn(Fact.Reach.class::isInstance)
                .extracting(f -> ((Fact.Reach) f).chain())
                .containsExactly("Api.run → Api.first → Api.shared");
    }

    @Test
    void mongoAndMapperRepositoriesAndYamlScalarFiltersProduceOnlySupportedTables() {
        var facts = TempRepo.at(tmp.resolve("data")).app()
                .java("x/Models.java", """
                        package x;
                        @Entity class AccountRecord {}
                        class DocumentRecord {}
                        interface Cycle<T> extends Cycle<T> {}
                        interface CircularRecordRepository extends Cycle<DocumentRecord> {}
                        interface RawUnknown extends CustomBase {}
                        interface ParameterizedUnknown extends CustomBase<AccountRecord> {}
                        interface Records extends ReactiveMongoRepository<DocumentRecord, Long> {}
                        interface Mapper extends MPJBaseMapper<AccountRecord> {}
                        interface MyBatisMapper {}
                        class MapperClass {}
                        """)
                .resource("mapper/interface.xml", "<mapper namespace=\"x.MyBatisMapper\"><resultMap id=\"r\" type=\"x.AccountRecord\"/><select id=\"a\">select * from interface_rows</select></mapper>")
                .resource("mapper/class.xml", "<mapper namespace=\"x.MapperClass\"><resultMap id=\"r\" type=\"x.AccountRecord\"/><select id=\"a\">select * from ignored_class</select></mapper>")
                .resource("mapper/unknown.xml", "<mapper namespace=\"x.WrongNamespace\"><resultMap id=\"r\" type=\"x.AccountRecord\"/><select id=\"a\">select * from ignored_interface</select></mapper>")
                .resource("db/change.yaml", """
                        databaseChangeLog:
                          - createTable:
                              tableName: yaml_rows
                              columns: [id]
                          - createTable: scalar_table
                          - ? [createTable]
                            : tableName: ignored_key
                          - createTable:
                              tableName: [non, scalar]
                        """).facts(new DataExtractor());

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .extracting(f -> {
                    var t = (Fact.TableUse) f;
                    return t.store() + ":" + t.owns() + ":" + t.table().text();
                }).containsExactlyInAnyOrder("mongo:false:documentRecord", "sql:false:account_record", "sql:false:account_record", "sql:false:account_record",
                        "sql:false:interface_rows", "sql:false:ignored_class", "sql:false:ignored_interface", "sql:true:yaml_rows");
    }

    // FINDING: OpenAPI path keys that are YAML collections become an empty path and are exposed as root endpoints.
    @Test
    void openApiSkipsBuildTargetTestsAndHandlesRootContextAndNonScalarNodes() {
        var repo = TempRepo.at(tmp.resolve("openapi")).app()
                .file("openapi.yaml", """
                        openapi: 3.0.3
                        paths:
                          /ok:
                            get: {}
                          /object:
                            post: []
                          ? [non, scalar]
                          :
                            get: {}
                        """)
                .file("build/spec.yaml", "openapi: 3.0.3\npaths:\n  /build:\n    get: {}\n")
                .file("target/spec.yaml", "openapi: 3.0.3\npaths:\n  /target:\n    get: {}\n")
                .file("src/test/resources/spec.yaml", "openapi: 3.0.3\npaths:\n  /test:\n    get: {}\n")
                .resource("application.properties", "server.servlet.context-path=/\n");

        assertThat(OpenApiSpec.endpoints(repo.service(), Value.exact("/")))
                .extracting(e -> e.method() + " " + e.path().text())
                .containsExactlyInAnyOrder("GET /ok", "POST /object", "GET /");
        assertThat(OpenApiSpec.endpoints(repo.service(), Value.exact("")))
                .extracting(e -> e.path().text()).containsExactlyInAnyOrder("/ok", "/object", "/");
    }
}
