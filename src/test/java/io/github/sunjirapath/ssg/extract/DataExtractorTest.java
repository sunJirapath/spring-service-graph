package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.scan.Discovery;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Repo;
import io.github.sunjirapath.ssg.scan.Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DataExtractorTest {

    @Test
    void mapsMongoDocumentsAndRepository(@TempDir Path repo) throws IOException {
        var facts = extract(repo, """
                package x;
                @Document(collection = "accounts") class Account {}
                @Document class UserProfile {}
                interface ProfileRepo extends MongoRepository<UserProfile, String> {}
                """);

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .extracting(f -> {
                    var t = (Fact.TableUse) f;
                    return t.owns() + " " + t.table().text() + " " + t.store();
                })
                .containsExactlyInAnyOrder("false accounts mongo", "false userProfile mongo", "false userProfile mongo");
    }

    @Test
    void usesSpringJpaDefaultTableName(@TempDir Path repo) throws IOException {
        var facts = extract(repo, "package x; @Entity class AccountHolder {}");

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TableUse.class, t -> {
            assertThat(t.owns()).isFalse();
            assertThat(t.table().text()).isEqualTo("account_holder");
            assertThat(t.store()).isEqualTo("sql");
        });
    }

    @Test
    void readsFlywayTablesAndEvidenceLines(@TempDir Path repo) throws IOException {
        var facts = extract(repo, "package x; class Empty {}",
                "db/migration/V1__init.sql", """
                        -- initial schema
                        CREATE TABLE IF NOT EXISTS public."Orders" (id bigint);
                        create table order_line (id int);
                        """);

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .extracting(f -> {
                    var t = (Fact.TableUse) f;
                    return t.owns() + " " + t.table().text() + " " + t.via() + " " + t.evidence().line();
                })
                .containsExactlyInAnyOrder("true orders flyway 2", "true order_line flyway 3");
    }

    @Test
    void ownsUsedTablesFromRootSqlFolder(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("sql"));
        Files.writeString(repo.resolve("sql/x.sql"), "-- schema\nCREATE TABLE `used_t` (id int);\nCREATE TABLE other_t (id int);\n");
        var facts = extract(repo, "package x; @Entity class UsedT {}");

        assertThat(facts).filteredOn(f -> f instanceof Fact.TableUse t && t.owns()).singleElement()
                .isInstanceOfSatisfying(Fact.TableUse.class, t -> {
                    assertThat(t.table().text()).isEqualTo("used_t");
                    assertThat(t.evidence().line()).isEqualTo(2);
                });
    }

    @Test
    void readsMultilineLiquibaseCreateTable(@TempDir Path repo) throws IOException {
        var facts = extract(repo, "package x; class Empty {}", "db/changelog/changes.xml", """
                <databaseChangeLog>
                  <createTable
                      tableName="payments"/>
                </databaseChangeLog>
                """);

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TableUse.class, t -> {
            assertThat(t.owns()).isTrue();
            assertThat(t.table().text()).isEqualTo("payments");
            assertThat(t.via()).isEqualTo("liquibase");
            assertThat(t.evidence().line()).isEqualTo(2);
        });
    }

    @Test
    void readsTablesFromMyBatisMapperXml(@TempDir Path repo) throws IOException {
        var facts = extract(repo, "package x; class Empty {}", "mapper/UserMapper.xml", """
                <mapper namespace="x.UserMapper">
                  <select id="find" resultType="x.User">
                    select * from users u
                    <where><if test="id != null">u.id = #{id}</if></where>
                  </select>
                  <update id="touch">update audit set n = 1</update>
                </mapper>
                """);

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .extracting(f -> {
                    var t = (Fact.TableUse) f;
                    return t.owns() + " " + t.table().text() + " " + t.via() + " " + t.evidence().line();
                })
                .containsExactlyInAnyOrder("false users mybatis-xml 2", "false audit mybatis-xml 6");
    }

    @Test
    void foldsMapperSqlFragmentsIntoStatementsAndSurvivesCycles(@TempDir Path repo) throws IOException {
        var facts = extract(repo, "package x; class Empty {}", "mapper/OrderMapper.xml", """
                <mapper namespace="x.OrderMapper">
                  <sql id="from">from orders o <include refid="joins"/></sql>
                  <sql id="joins">join items i on i.oid = o.id <include refid="x.OrderMapper.from"/></sql>
                  <select id="find">select * <include refid="from"></include></select>
                  <select id="gone">select 1 <include refid="other.ns.missing"/></select>
                </mapper>
                """);

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance)
                .extracting(f -> ((Fact.TableUse) f).table().text())
                .containsExactly("orders", "items");
    }

    @Test
    void readsTableAnnotationWithoutEntity(@TempDir Path repo) throws IOException {
        var facts = extract(repo, "package x; @Table(name = \"mdc_address\") class MdcAddress {}");

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TableUse.class,
                t -> assertThat(t.table().text()).isEqualTo("mdc_address"));
    }

    @Test
    void reportsRepositoryEntityMissingFromSources(@TempDir Path repo) throws IOException {
        var facts = extract(repo, "package x; interface Ghosts extends JpaRepository<Ghost, Long> {}");

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.Problem.class,
                p -> assertThat(p.kind()).isEqualTo("UNRESOLVED"));
    }

    private static java.util.List<Fact> extract(Path repo, String source) throws IOException {
        return new DataExtractor().extract(service(repo, source));
    }

    private static java.util.List<Fact> extract(Path repo, String source, String resource, String content) throws IOException {
        Path file = repo.resolve("src/main/resources").resolve(resource);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return new DataExtractor().extract(service(repo, source));
    }

    private static Service service(Path repo, String source) throws IOException {
        Path src = repo.resolve("src/main/java/x");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package x; @SpringBootApplication class App {}");
        Files.writeString(src.resolve("Data.java"), source);
        return Discovery.discover(JavaIndex.build(Repo.of(repo)), "default", null).getFirst();
    }
}
