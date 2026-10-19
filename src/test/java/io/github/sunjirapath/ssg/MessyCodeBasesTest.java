package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.extract.DataExtractor;
import io.github.sunjirapath.ssg.extract.InboundHttpExtractor;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.scan.Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Gaps #3, #5 and #7 from the messy real-world benchmark (yudao-cloud, dolphinscheduler, fineract). */
class MessyCodeBasesTest {

    @Test
    void myBatisPlusMappersAreRepositoriesOfTableNameEntities(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("system")).app()
                .java("app/UserDO.java", "package app; @TableName(\"system_users\") class UserDO {}")
                .java("app/RoleDO.java", "package app; @TableName(value = \"system_role\") class RoleDO {}")
                .java("app/DeptDO.java", "package app; @TableName class DeptDO {}")
                .java("app/BaseMapperX.java", "package app; interface BaseMapperX<T> extends MPJBaseMapper<T> {}")
                .java("app/UserMapper.java", "package app; @Mapper interface UserMapper extends BaseMapperX<UserDO> {}")
                .java("app/RoleMapper.java", "package app; interface RoleMapper extends BaseMapper<RoleDO> {}")
                .java("app/UserController.java", """
                        package app;
                        @RestController class UserController {
                            private UserMapper userMapper;
                            @GetMapping("/users/{id}") Object get(Long id) { return userMapper.selectById(id); }
                        }
                        """);

        var facts = repo.allFacts();

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance).map(Fact.TableUse.class::cast)
                .extracting(t -> t.table().text() + " " + t.via())
                .containsExactlyInAnyOrder("system_users @TableName", "system_role @TableName", "dept_do @TableName",
                        "system_users repository UserMapper", "system_role repository RoleMapper");
        assertThat(facts).filteredOn(Fact.Problem.class::isInstance).isEmpty();
        assertThat(facts).filteredOn(Fact.Reach.class::isInstance).map(Fact.Reach.class::cast)
                .singleElement().satisfies(r -> {
                    assertThat(r.path()).isEqualTo("/users/{id}");
                    assertThat(r.chain()).isEqualTo("UserController.get → userMapper.selectById");
                });
    }

    @Test
    void myBatisPlusEntityWithoutTableNameMapsToSnakeCaseTable(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("pig")).app()
                .java("app/SysJob.java", "package app; class SysJob extends Model<SysJob> {}")
                .java("app/SysJobMapper.java", "package app; @Mapper interface SysJobMapper extends BaseMapper<SysJob> {}")
                .java("app/GoneMapper.java", "package app; interface GoneMapper extends BaseMapper<Gone> {}")
                .java("app/Doc.java", "package app; class Doc {}")
                .java("app/DocRepository.java", "package app; interface DocRepository extends MongoRepository<Doc, String> {}");

        var facts = repo.facts(new DataExtractor());

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance).map(Fact.TableUse.class::cast)
                .extracting(t -> t.table().text() + " " + t.via()).containsExactly("sys_job repository SysJobMapper");
        assertThat(facts).filteredOn(Fact.Problem.class::isInstance).map(Fact.Problem.class::cast)
                .extracting(Fact.Problem::message).containsExactlyInAnyOrder(
                        "repository GoneMapper: entity Gone not found in sources",
                        "repository DocRepository: entity Doc not found in sources");
    }

    @Test
    void mapStructStyleMappersAreNotRepositories(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("shop")).app()
                .java("app/UserDto.java", "package app; class UserDto {}")
                .java("app/UserMapper.java", "package app; @Mapper interface UserMapper extends EntityMapper<UserDto, Object> {}");

        assertThat(repo.facts(new DataExtractor())).isEmpty();
    }

    @Test
    void appBootedFromMainWithoutSpringBootApplicationIsAService(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("fineract"))
                .file("provider/src/main/java/org/ServerApplication.java", """
                        package org;
                        public class ServerApplication extends SpringBootServletInitializer {
                            public static void main(String[] args) {
                                configure(new SpringApplicationBuilder(ServerApplication.class)).run(args);
                            }
                        }
                        """)
                .file("provider/src/main/resources/application.properties", "spring.application.name=fineract\n")
                .file("other/src/main/java/o/Plain.java", """
                        package o;
                        public class Plain { public static void main(String[] args) { System.out.println("no spring"); } }
                        """)
                .file("tool/src/main/java/t/Tool.java", """
                        package t;
                        class Tool { public static void main(String[] args) { org.springframework.boot.SpringApplication.run(Config.class, args); } }
                        """);

        assertThat(repo.services()).extracting(Service::name).containsExactlyInAnyOrder("fineract", "tool");
    }

    @Test
    void frameworkInterfacesAreNotMissingInterfaces(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("api")).app()
                .java("app/DsErrorController.java", """
                        package app;
                        import org.springframework.boot.web.servlet.error.ErrorController;
                        @Controller class DsErrorController implements ErrorController, java.io.Serializable {}
                        """)
                .java("app/OrdersController.java", """
                        package app;
                        import com.acme.api.OrdersApi;
                        @RestController class OrdersController implements OrdersApi {}
                        """);

        assertThat(repo.facts(new InboundHttpExtractor())).filteredOn(Fact.Problem.class::isInstance)
                .map(Fact.Problem.class::cast).extracting(Fact.Problem::message)
                .singleElement().asString().startsWith("controller OrdersController implements OrdersApi");
    }
}
