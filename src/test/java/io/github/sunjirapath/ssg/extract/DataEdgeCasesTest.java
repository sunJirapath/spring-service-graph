package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DataEdgeCasesTest {

    @Test
    void entityDefaultNameUsesSpringSnakeCase(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; @Entity class AccountHolder {} @Entity class HTTPLog {} @Entity class URLMapping {}")
                .facts(new DataExtractor());

        // EXPECTATION?: Acronym splitting varies between Spring/Hibernate naming strategies; these match Hibernate's default physical naming.
        assertThat(tables(facts, false)).containsExactlyInAnyOrder("account_holder", "httplog", "urlmapping");
    }

    @Test
    void explicitTableNamesAreLowerCasedAndSchemaIsDiscarded(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @Entity @Table(name = "MixedCase") class A {}
                @Entity @Table(schema = "billing", name = "Orders") class B {}
                @Entity @Table(name = "\\\"QuotedTable\\\"") class C {}
                """).facts(new DataExtractor());

        assertThat(tables(facts, false)).containsExactlyInAnyOrder("mixedcase", "orders", "quotedtable");
    }

    @Test
    void tableNameConstantIsResolved(@TempDir Path tmp) {
        var fact = table(repo(tmp, "package x; class Names { static final String TABLE = \"Ledger\"; } @Entity @Table(name = Names.TABLE) class Entry {}")
                .facts(new DataExtractor()), "@Entity");

        assertThat(fact.table().text()).isEqualTo("ledger");
        assertThat(fact.table().confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void mappedSuperclassAloneDoesNotCreateTableButAbstractEntityDoes(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; @MappedSuperclass abstract class Base {} @Entity @MappedSuperclass abstract class PersistentBase {}")
                .facts(new DataExtractor());

        assertThat(tables(facts, false)).containsExactly("persistent_base");
    }

    @Test
    void mongoDocumentSupportsCollectionValueAndDefault(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @Document(collection = "accounts") class Account {}
                @Document("events") class Event {}
                @Document class UserProfile {}
                """).facts(new DataExtractor());

        assertThat(tableUses(facts))
                .allSatisfy(t -> assertThat(t.store()).isEqualTo("mongo"));
        assertThat(tables(facts, false)).containsExactlyInAnyOrder("accounts", "events", "userProfile");
    }

    @Test
    void elasticsearchDocumentIsNotAMongoCollection(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                import org.springframework.data.elasticsearch.annotations.Document;
                @Document(indexName = "pms") class EsProduct {}
                """).facts(new DataExtractor());

        assertThat(tableUses(facts)).isEmpty();
    }

    @Test
    void allStandardSpringDataRepositoryKindsMapTheirEntity(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @Entity class Item {}
                interface ARepository extends JpaRepository<Item, Long> {}
                interface BRepository extends CrudRepository<Item, Long> {}
                interface CRepository extends MongoRepository<Item, Long> {}
                interface DRepository extends ReactiveCrudRepository<Item, Long> {}
                interface ERepository extends PagingAndSortingRepository<Item, Long> {}
                """).facts(new DataExtractor());

        assertThat(tableUses(facts))
                .filteredOn(t -> t.via().startsWith("repository ")).hasSize(5)
                .allSatisfy(t -> assertThat(t.table().text()).isEqualTo("item"));
    }

    @Test
    void customGenericRepositoryBaseResolvesConcreteEntity(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @Entity class Order {}
                interface BaseRepo<T, ID> extends JpaRepository<T, ID> {}
                interface OrderRepo extends BaseRepo<Order, Long> {}
                """).facts(new DataExtractor());

        // EXPECTATION?: Resolving inherited generic substitutions is necessary for common enterprise repository bases.
        assertThat(tableUses(facts))
                .filteredOn(t -> t.via().equals("repository OrderRepo")).singleElement()
                .satisfies(t -> assertThat(t.table().text()).isEqualTo("order"));
    }

    @Test
    void repositoryCanReferToNestedEntity(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Outer { @Entity static class Entry {} } interface Entries extends JpaRepository<Outer.Entry, Long> {}")
                .facts(new DataExtractor());

        assertThat(tableUses(facts))
                .filteredOn(t -> t.via().equals("repository Entries")).singleElement()
                .satisfies(t -> assertThat(t.table().text()).isEqualTo("entry"));
    }

    @Test
    void repositoryCanResolveEntityFromLibraryModule(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("fleet"))
                .file("shared/src/main/java/shared/ExternalEntity.java", "package shared; @Entity public class ExternalEntity {}")
                .file("service/src/main/java/app/App.java", "package app; @SpringBootApplication class App {}")
                .file("service/src/main/java/app/Repo.java", "package app; import shared.ExternalEntity; interface ExternalRepository extends JpaRepository<ExternalEntity, Long> {}")
                .file("service/src/main/resources/application.properties", "spring.application.name=service\n");

        assertThat(tableUses(new DataExtractor().extract(repo.service("service"))))
                .filteredOn(t -> t.via().equals("repository ExternalRepository")).singleElement()
                .satisfies(t -> assertThat(t.table().text()).isEqualTo("external_entity"));
    }

    @Test
    void repositoryWithMissingEntityProducesUnresolvedProblem(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; interface GhostRepository extends JpaRepository<Ghost, Long> {}")
                .facts(new DataExtractor());

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.Problem.class, p -> {
            assertThat(p.kind()).isEqualTo("UNRESOLVED");
            assertThat(p.message()).contains("GhostRepository").contains("Ghost");
        });
    }

    @Test
    void repositoryWithoutEntityTypeArgumentCreatesNoFact(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; interface RawRepository extends JpaRepository {}")
                .facts(new DataExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void repositoryMethodsDoNotDuplicateRepositoryFact(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @Entity class Customer {}
                interface CustomerRepository extends JpaRepository<Customer, Long> {
                    Customer findByEmail(String email);
                    @Query("select c from Customer c") Customer custom();
                }
                """).facts(new DataExtractor());

        assertThat(tableUses(facts))
                .filteredOn(t -> t.via().equals("repository CustomerRepository")).hasSize(1);
    }

    @Test
    void sqlRecognizesFlywayAndSchemaVariants(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("db/migration/V1__init.sql", "CREATE TABLE IF NOT EXISTS one(id int);\nCREATE TEMPORARY TABLE two(id int);")
                .resource("db/migration/V1_1__more.sql", "CREATE CACHED TABLE three(id int);\nCREATE MEMORY TABLE four(id int);")
                .resource("db/migration/R__repeatable.sql", "create table five(id int);")
                .resource("schema.sql", "create table six(id int);")
                .facts(new DataExtractor());

        assertThat(tables(facts, true)).containsExactlyInAnyOrder("one", "two", "three", "four", "five", "six");
        assertThat(tableUses(facts))
                .filteredOn(t -> t.via().equals("flyway")).hasSize(5);
        assertThat(tableUses(facts)).filteredOn(t -> t.table().text().equals("two")).singleElement()
                .satisfies(t -> assertThat(t.evidence().line()).isEqualTo(2));
    }

    @Test
    void sqlNormalizesQuotedSchemaBackticksAndBrackets(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("schema.sql", """
                        CREATE TABLE "Sales"."OrderLine" (id int);
                        CREATE TABLE `Audit`.`Events` (id int);
                        CREATE TABLE [dbo].[Ledger] (id int);
                        create table lowercase_name (id int);
                        """).facts(new DataExtractor());

        assertThat(tables(facts, true)).containsExactlyInAnyOrder("orderline", "events", "ledger", "lowercase_name");
    }

    @Test
    void sqlIgnoresViewsAltersAndDataWithoutCreate(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("schema.sql", "CREATE VIEW v AS SELECT 1;\nALTER TABLE old ADD x int;")
                .resource("data.sql", "INSERT INTO old VALUES (1);")
                .facts(new DataExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void sqlCreateTableInsideCommentIsIgnored(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("schema.sql", "-- CREATE TABLE ghost(id int);\n/* CREATE TABLE phantom(id int); */")
                .facts(new DataExtractor());

        // EXPECTATION?: D6 permits a regex, but reporting commented-out DDL is a harmful ownership false positive.
        assertThat(facts).isEmpty();
    }

    @Test
    void liquibaseXmlHandlesNamespacesAttributeOrderAndEvidence(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("db/changelog/db.xml", """
                        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                          <changeSet id="1" author="a"><createTable schemaName="s"
                              remarks="x" tableName="First"/></changeSet>
                          <changeSet id="2" author="a">
                            <createTable tableName="Second"/>
                          </changeSet>
                        </databaseChangeLog>
                        """).facts(new DataExtractor());

        assertThat(tableUses(facts))
                .extracting(t -> t.table().text() + ":" + t.evidence().line())
                .containsExactlyInAnyOrder("first:2", "second:5");
    }

    @Test
    void liquibaseYamlWalksNestedChangeSetsAndKeepsValueLine(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("db/changelog/db.yaml", """
                        databaseChangeLog:
                          - changeSet:
                              id: one
                              changes:
                                - createTable:
                                    tableName: First
                          - changeSet:
                              id: two
                              changes:
                                - createTable: { tableName: Second }
                        """).facts(new DataExtractor());

        assertThat(tableUses(facts))
                .extracting(t -> t.table().text() + ":" + t.evidence().line())
                .containsExactlyInAnyOrder("first:6", "second:10");
    }

    @Test
    void liquibaseJsonChangelogIsParsed(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("db/changelog/db.json", """
                        {"databaseChangeLog":[
                          {"changeSet":{"id":"1","changes":[
                            {"createTable":{"tableName":"JsonTable"}}
                          ]}}
                        ]}
                        """).facts(new DataExtractor());

        assertThat(tableUses(facts)).singleElement().satisfies(t -> {
            assertThat(t.table().text()).isEqualTo("jsontable");
            assertThat(t.evidence().line()).isEqualTo(3);
        });
    }

    @Test
    void arbitraryYamlIsNotTreatedAsLiquibase(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("other.yml", "createTable:\n  tableName: ghost\n")
                .facts(new DataExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void missingResourcesAndBinaryResourceDoNotCrash(@TempDir Path tmp) {
        var withoutResources = repo(tmp.resolve("a"), "package x; class Empty {}");
        var binary = repo(tmp.resolve("b"), "package x; class Empty {}")
                .bytes("src/main/resources/db/migration/V1__binary.sql", new byte[]{0, (byte) 0xff, 1});

        assertThatCode(() -> new DataExtractor().extract(withoutResources.service())).doesNotThrowAnyException();
        assertThatCode(() -> new DataExtractor().extract(binary.service())).doesNotThrowAnyException();
    }

    private static TempRepo repo(Path tmp, String source) {
        return TempRepo.at(tmp.resolve("repo")).app().java("x/Data.java", source);
    }

    private static List<String> tables(List<Fact> facts, boolean owns) {
        return tableUses(facts).stream()
                .filter(t -> t.owns() == owns).map(t -> t.table().text()).toList();
    }

    private static List<Fact.TableUse> tableUses(List<Fact> facts) {
        return facts.stream().filter(Fact.TableUse.class::isInstance).map(Fact.TableUse.class::cast).toList();
    }

    private static Fact.TableUse table(List<Fact> facts, String via) {
        return facts.stream().filter(Fact.TableUse.class::isInstance).map(Fact.TableUse.class::cast)
                .filter(t -> t.via().equals(via)).findFirst().orElseThrow();
    }
}
