package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcReachTest {

    @Test
    void endpointReachesEveryJdbcTableThroughServiceMethod(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("orders-repo")).app()
                .resource("application.yml", "spring.application.name: orders-service")
                .java("x/Orders.java", """
                        package x;
                        @RestController class OrdersController {
                            OrdersService service;
                            @GetMapping("/orders/{id}") Object get(String id) { return service.load(id); }
                        }
                        @Service class OrdersService {
                            JdbcTemplate jdbc;
                            Object load(String id) {
                                return jdbc.query("SELECT * FROM orders JOIN order_lines ON order_lines.order_id = orders.id");
                            }
                        }
                        """);

        Graph graph = TempRepo.scan(repo);

        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.USES && e.from().equals("service:orders-service"))
                .extracting(Edge::to)
                .containsExactlyInAnyOrder("table:orders-service/orders", "table:orders-service/order_lines");
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.REACHES
                        && e.from().equals("endpoint:orders-service:GET /orders/{id}"))
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.attrs()).containsEntry("chain", "OrdersController.get → OrdersService.load"))
                .extracting(Edge::to)
                .containsExactlyInAnyOrder("table:orders-service/orders", "table:orders-service/order_lines");
    }

    @Test
    void endpointReachesTableThroughConfiguredWrapperWithoutPassThroughFinding(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("wrapper-repo")).app()
                .resource("application.yml", "spring.application.name: wrapper-service")
                .java("x/Orders.java", """
                        package x;
                        @RestController class OrdersController {
                            OrdersService service;
                            @GetMapping("/orders") Object get() { return service.load(); }
                        }
                        @Service class OrdersService {
                            Db db;
                            Object load() { return db.exec("SELECT * FROM wrapped_orders"); }
                        }
                        class Db {
                            JdbcTemplate jdbc;
                            Object exec(String sql) { return jdbc.query(sql); }
                        }
                        """);
        var options = new Pipeline.Options(List.of(repo.root()), "default", null, null, List.of("Db.exec"));

        Graph graph = TempRepo.scan(options);

        assertThat(graph.edges()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(Edge.Kind.REACHES);
            assertThat(e.from()).isEqualTo("endpoint:wrapper-service:GET /orders");
            assertThat(e.to()).isEqualTo("table:wrapper-service/wrapped_orders");
            assertThat(e.attrs()).containsEntry("chain", "OrdersController.get → OrdersService.load");
        });
        assertThat(graph.findings()).noneMatch(f -> f.kind() == Finding.Kind.UNRESOLVED);
    }

    @Test
    void flywayOwnershipAndJdbcUsageShareOneTableNode(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("inventory-repo")).app()
                .resource("application.yml", "spring.application.name: inventory-service")
                .resource("db/migration/V1__orders.sql", "CREATE TABLE orders (id bigint primary key);")
                .java("x/Queries.java", """
                        package x;
                        class Queries {
                            JdbcTemplate jdbc;
                            Object load() { return jdbc.query("SELECT * FROM orders"); }
                        }
                        """);

        Graph graph = TempRepo.scan(repo);

        assertThat(graph.nodes()).filteredOn(n -> n.id().equals("table:inventory-service/orders"))
                .singleElement().isInstanceOf(Node.Table.class);
        assertThat(graph.edges()).filteredOn(e -> e.to().equals("table:inventory-service/orders"))
                .extracting(Edge::kind)
                .containsExactlyInAnyOrder(Edge.Kind.OWNS, Edge.Kind.USES);
    }

    @Test
    void mainAcceptsCommaSeparatedSqlMethodsAndWritesTheirTables(@TempDir Path tmp) throws Exception {
        var repo = TempRepo.at(tmp.resolve("cli-repo")).app()
                .resource("application.yml", "spring.application.name: cli-service")
                .java("x/Queries.java", """
                        package x;
                        class Db { Object exec(String sql) { return null; } }
                        class Other { Object run(String sql) { return null; } }
                        class Queries {
                            Db db;
                            Other other;
                            void load() {
                                db.exec("SELECT * FROM cli_orders");
                                other.run("DELETE FROM cli_audit");
                            }
                        }
                        """);
        Path out = tmp.resolve("out");

        int exit = Main.run(new String[]{"scan", "--repos", repo.root().toString(), "--sql-methods",
                "Db.exec,Other.run", "--out", out.toString()});
        Graph graph = JsonOutput.read(out);

        assertThat(exit).isZero();
        assertThat(graph.nodes()).filteredOn(Node.Table.class::isInstance).extracting(Node::id)
                .contains("table:cli-service/cli_orders", "table:cli-service/cli_audit");
    }
}
