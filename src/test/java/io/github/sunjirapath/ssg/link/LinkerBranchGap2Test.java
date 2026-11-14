package io.github.sunjirapath.ssg.link;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LinkerBranchGap2Test {
    @Test
    void ownershipDemotionRequiresSameRepositoryAndModuleAndDeduplicatesOwners() {
        var ddl = new Fact.TableUse(true, Value.exact("orders"), "sql", ev("repo-a", "service/schema.sql"), "sql");
        var foreign = new Fact.TableUse(true, Value.exact("orders"), "sql", ev("repo-b", "service/schema.sql"), "sql");
        var graph = Linker.link(List.of(
                new Linker.ServiceFacts("owner", "repo-a", "service", List.of(ddl, ddl)),
                new Linker.ServiceFacts("foreign", "repo-b", "service", List.of(foreign))), Map.of(), List.of());

        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.OWNS || e.kind() == Edge.Kind.USES)
                .extracting(e -> e.kind() + ":" + e.from()).containsExactlyInAnyOrder("OWNS:service:owner", "OWNS:service:foreign");
        assertThat(graph.findings()).filteredOn(f -> f.kind().name().equals("SHARED_TABLE"))
                .singleElement().satisfies(f -> assertThat(f.message()).isEqualTo("table `orders` is owned by 2 services: foreign, owner"));
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.OWNS)
                .allSatisfy(e -> assertThat(e.evidence()).hasSize(1));
    }

    @Test
    void unresolvedTablesDoNotCreateSharedTableFindingsAndRootModuleOwnsItsDdl() {
        var unresolved = new Fact.TableUse(true, Value.unresolved("tableName"), "sql", ev("repo", "a/schema.sql"), "sql");
        var root = new Fact.TableUse(true, Value.exact("root_table"), "sql", ev("repo", "schema.sql"), "sql");
        var graph = Linker.link(List.of(
                new Linker.ServiceFacts("a", "repo", ".", List.of(unresolved, root)),
                new Linker.ServiceFacts("b", "repo", "b", List.of(unresolved))), Map.of(), List.of());

        assertThat(graph.findings()).filteredOn(f -> f.kind().name().equals("SHARED_TABLE")).isEmpty();
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.OWNS || e.kind() == Edge.Kind.USES)
                .extracting(e -> e.kind() + ":" + e.to()).containsExactlyInAnyOrder(
                        "OWNS:table:sql:a/{?}", "OWNS:table:sql:a/root_table", "USES:table:sql:b/{?}");
    }

    @Test
    void uniqueModuleOwnerDemotesSameTableFactsFromOtherReposAndPaths() {
        var own = new Fact.TableUse(true, Value.exact("orders"), "sql", ev("repo", "service/schema.sql"), "sql");
        var wrongRepo = new Fact.TableUse(true, Value.exact("orders"), "sql", ev("repo", "service/schema.sql"), "sql");
        var wrongPath = new Fact.TableUse(true, Value.exact("orders"), "sql", ev("repo", "service/schema.sql"), "sql");
        var unrelated = new Fact.TableUse(true, Value.exact("audit"), "sql", ev("repo", "different/schema.sql"), "sql");
        var graph = Linker.link(List.of(
                new Linker.ServiceFacts("owner", "repo", "service", List.of(own)),
                new Linker.ServiceFacts("other-repo", "elsewhere", "service", List.of(wrongRepo, unrelated)),
                new Linker.ServiceFacts("other-path", "repo", "different", List.of(wrongPath))), Map.of(), List.of());

        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.OWNS || e.kind() == Edge.Kind.USES)
                .extracting(e -> e.kind() + ":" + e.from()).containsExactlyInAnyOrder(
                        "OWNS:service:owner", "USES:service:other-repo", "OWNS:service:other-repo", "USES:service:other-path");
        assertThat(graph.findings()).filteredOn(f -> f.kind().name().equals("SHARED_TABLE")).isEmpty();
    }

    private static Evidence ev(String repo, String file) { return new Evidence(repo, file, 1); }
}
