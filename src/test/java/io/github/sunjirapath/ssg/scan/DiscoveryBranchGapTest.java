package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryBranchGapTest {

    @TempDir
    Path tmp;

    // FINDING: unnamed modules with identical last eight path segments receive duplicate service names.
    @Test
    void fallbackNamesStopDistinguishingModulesAfterEightSegments() {
        var repo = TempRepo.at(tmp.resolve("deep-modules"))
                .file("a/p/q/r/s/t/u/v/http/src/main/java/first/App.java",
                        "package first; @SpringBootApplication class App {}")
                .file("b/p/q/r/s/t/u/v/http/src/main/java/second/App.java",
                        "package second; @SpringBootApplication class App {}");

        var services = repo.services();

        assertThat(services).extracting(Service::name)
                .containsExactly("p-q-r-s-t-u-v-http", "p-q-r-s-t-u-v-http");
        assertThat(services).extracting(Service::path).containsExactly(
                "a/p/q/r/s/t/u/v/http", "b/p/q/r/s/t/u/v/http");
    }

    @ParameterizedTest
    @ValueSource(strings = {"run(args);", "Other.run(args);"})
    void mainRunCallsWithoutASpringApplicationScopeDoNotCreateServices(String startup) {
        var repo = TempRepo.at(tmp.resolve("not-spring"))
                .java("launch/App.java", """
                        package launch;
                        class App {
                            public static void main(String[] args) { %s }
                            static void run(String[] args) {}
                        }
                        class Other { static void run(String[] args) {} }
                        """.formatted(startup));

        assertThat(repo.services()).isEmpty();
    }

    // Pins Discovery.java:164: a bare statically imported RUNTIME retention value is accepted.
    @Test
    void composedApplicationAnnotationsAcceptBareRuntimeRetention() {
        var repo = TempRepo.at(tmp.resolve("retention"))
                .java("launch/App.java", """
                        package launch;
                        import java.lang.annotation.*;
                        import static java.lang.annotation.RetentionPolicy.RUNTIME;
                        @Retention(RUNTIME) @SpringBootApplication @interface Boot {}
                        @Boot class App {}
                        """);

        assertThat(repo.services()).extracting(Service::name)
                .containsExactly("retention");
    }

    // Pins Discovery.java:155, 581: meta-annotation cycle guards terminate discovery and scan traversal.
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void runtimeMetaAnnotationCyclesTerminateAndPreserveTheNoScanFallback() {
        var repo = library("""
                package start;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @B @interface A {}
                @Retention(RetentionPolicy.RUNTIME) @A @interface B {}
                @A @B class App {
                    public static void main(String[] args) { new SpringApplication().run(args); }
                }
                """)
                .file("lib/src/main/java/foreign/ForeignBean.java", "package foreign; @Service class ForeignBean {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ForeignBean.java");
    }

    // Pins Discovery.java:264-267, 270-275: nested bean methods and parent configurations contribute registrations.
    @Test
    void repeatedParentsAndNestedBeanMethodsLoadImportedAndEntitySources() {
        var repo = library("""
                package start;
                import configuration.Base;
                @SpringBootApplication class App extends Base {
                    interface NestedInterface {}
                    enum NestedEnum { ONE }
                    static class Ordinary { void helper() {} }
                    static class BeanOnly {
                        @Bean Object factory(LocalSessionFactoryBean bean) {
                            bean.setPackagesToScan("nested.entities");
                            return bean;
                        }
                    }
                }
                @SpringBootApplication class Second extends Base {}
                interface LocalContract {}
                record OwnRecord(String value) implements LocalContract {}
                """)
                .file("lib/src/main/java/configuration/Base.java", """
                        package configuration;
                        @Configuration @Import(Imported.class) public class Base {}
                        """)
                .file("lib/src/main/java/configuration/Imported.java",
                        "package configuration; interface Imported {}")
                .file("lib/src/main/java/nested/entities/ActiveEntity.java",
                        "package nested.entities; @Entity class ActiveEntity {}")
                .file("lib/src/main/java/unused/UnusedBean.java",
                        "package unused; @Service class UnusedBean {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder(
                "App.java", "Base.java", "Imported.java", "ActiveEntity.java");
    }

    @Test
    void enableAutoConfigurationSuppliesPersistenceDefaultsWithoutComponentScanning() {
        var repo = library("""
                package start;
                @EnableAutoConfiguration class App {
                    public static void main(String[] args) { new SpringApplication().run(args); }
                }
                """)
                .file("lib/src/main/java/start/data/DefaultRepository.java",
                        "package start.data; interface DefaultRepository extends ReactiveMongoRepository<Object, String> {}")
                .file("lib/src/main/java/start/data/DefaultEntity.java",
                        "package start.data; @Entity class DefaultEntity {}")
                .file("lib/src/main/java/start/data/UnscannedBean.java",
                        "package start.data; @Service class UnscannedBean {}")
                .file("lib/src/main/java/foreign/ForeignRepository.java",
                        "package foreign; interface ForeignRepository extends ReactiveMongoRepository<Object, String> {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder(
                "App.java", "DefaultRepository.java", "DefaultEntity.java");
    }

    @ParameterizedTest
    @CsvSource({
            "EnableJpaRepositories, JpaRepository",
            "EnableMongoRepositories, MongoRepository",
            "EnableReactiveMongoRepositories, ReactiveMongoRepository"
    })
    void repositoryScansWithoutPackagesUseTheDeclaringPackage(String annotation, String base) {
        var repo = library("package start; @SpringBootApplication(scanBasePackages=\"none\") @"
                + annotation + " class App {}")
                .file("lib/src/main/java/start/ActiveRepository.java",
                        "package start; interface ActiveRepository extends " + base + "<Object, String> {}")
                .file("lib/src/main/java/foreign/ForeignRepository.java",
                        "package foreign; interface ForeignRepository extends " + base + "<Object, String> {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ActiveRepository.java");
    }

    @Test
    void explicitReactiveMongoScanReplacesOnlyItsOwnDefaultPackage() {
        var repo = library("""
                package start;
                @SpringBootApplication(scanBasePackages="none")
                @EnableReactiveMongoRepositories("registered") class App {}
                """)
                .file("lib/src/main/java/registered/ActiveRepository.java",
                        "package registered; interface ActiveRepository extends ReactiveMongoRepository<Object, String> {}")
                .file("lib/src/main/java/start/DefaultReactiveRepository.java",
                        "package start; interface DefaultReactiveRepository extends ReactiveMongoRepository<Object, String> {}")
                .file("lib/src/main/java/start/DefaultJpaRepository.java",
                        "package start; interface DefaultJpaRepository extends JpaRepository<Object, String> {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder(
                "App.java", "ActiveRepository.java", "DefaultJpaRepository.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {"@Filter", "@Filter(type=FilterType.ANNOTATION, classes=Deprecated.class)"})
    void repositoryFiltersWithoutRegexTypeDoNotExcludeRepositories(String filter) {
        var repo = library("""
                package start;
                @SpringBootApplication(scanBasePackages="none")
                @EnableJpaRepositories(basePackages="domain", excludeFilters=%s) class App {}
                """.formatted(filter))
                .file("lib/src/main/java/domain/ActiveRepository.java",
                        "package domain; interface ActiveRepository extends JpaRepository<Object, String> {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ActiveRepository.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@EnableJpaRepositories(\"${missing}\")",
            "@EntityScan(basePackages=Missing.PACKAGES)",
            "@EnableJpaRepositories(basePackages=\"domain\", excludeFilters=@Filter(type=FilterType.REGEX, pattern=\"${missing}\"))",
            "@EnableJpaRepositories(basePackages=\"domain\", excludeFilters=@Filter(type=FilterType.REGEX, pattern=\"[\"))",
            "@MapperScan(\"${missing}\")",
            "@MapperScan(basePackageClasses=Missing.CLASSES)",
            "@MapperScan(basePackageClasses=Missing.class)",
            "@MapperScan(basePackageClasses=int.class)",
            "@MapperScan(annotationClass=Missing.ANNOTATION)",
            "@MapperScan(markerInterface=Missing.MARKER)"
    })
    void unreadablePersistenceOrMapperScansFallBackToLibraryBeans(String annotation) {
        var repo = library("package start; @SpringBootApplication " + annotation + " class App {}")
                .file("lib/src/main/java/start/InScan.java", "package start; @Service class InScan {}")
                .file("lib/src/main/java/foreign/OutOfScan.java", "package foreign; @Service class OutOfScan {}")
                .file("lib/src/main/java/domain/PlainMapper.java", "package domain; interface PlainMapper {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "InScan.java", "OutOfScan.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@MapperScan",
            "@MapperScan(value={\" \", \"\"}, annotationClass=java.lang.annotation.Annotation.class, markerInterface=java.lang.Class.class)"
    })
    void defaultMapperSentinelsAndBlankPackagesSelectHomePackageInterfaces(String annotation) {
        var repo = library("package start; @SpringBootApplication(scanBasePackages=\"none\") "
                + annotation + " class App {}")
                .file("lib/src/main/java/start/PlainMapper.java", "package start; interface PlainMapper {}")
                .file("lib/src/main/java/start/NotAnInterface.java", "package start; class NotAnInterface {}")
                .file("lib/src/main/java/start/NotAClass.java", "package start; enum NotAClass { ONE }")
                .file("lib/src/main/java/foreign/ForeignMapper.java", "package foreign; interface ForeignMapper {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "PlainMapper.java");
    }

    @Test
    void cyclicAndDiamondInterfacesTerminateWithoutMatchingMapperOrRepositoryBases() {
        var repo = library("""
                package start;
                @SpringBootApplication(scanBasePackages="none")
                @MapperScan(basePackages="domain", markerInterface=external.Marker.class)
                @EnableJpaRepositories("domain") class App {}
                """)
                .file("lib/src/main/java/domain/ActiveMapper.java",
                        "package domain; interface ActiveMapper extends external.Marker {}")
                .file("lib/src/main/java/domain/ActiveRepository.java",
                        "package domain; interface ActiveRepository extends JpaRepository<Object, String> {}")
                .file("lib/src/main/java/domain/NoMatch.java", """
                        package domain;
                        interface NoMatch extends Left, Right {}
                        interface Left extends Shared {}
                        interface Right extends Shared {}
                        interface Shared {}
                        interface CycleA extends CycleB {}
                        interface CycleB extends CycleA {}
                        @NoRepositoryBean interface AbstractRepository extends JpaRepository<Object, String> {}
                        """);

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ActiveMapper.java", "ActiveRepository.java");
    }

    @Test
    void resolvedMapperMarkersRejectAnUnrelatedInterfaceWithTheSameSimpleName() {
        var repo = library("""
                package start;
                @SpringBootApplication(scanBasePackages="none")
                @MapperScan(basePackages="mappers", markerInterface=contracts.Marker.class) class App {}
                """)
                .file("lib/src/main/java/contracts/Marker.java", "package contracts; public interface Marker {}")
                .file("lib/src/main/java/decoy/Marker.java", "package decoy; public interface Marker {}")
                .file("lib/src/main/java/mappers/ActiveMapper.java",
                        "package mappers; interface ActiveMapper extends contracts.Marker {}")
                .file("lib/src/main/java/mappers/WrongMapper.java",
                        "package mappers; interface WrongMapper extends decoy.Marker {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "Marker.java", "ActiveMapper.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {"@EnableFeignClients(\"${missing}\")", "@EnableFeignClients(basePackages=Missing.PACKAGES)"})
    void unknownFeignPackagesDoNotFallBackToEveryLibraryBean(String annotation) {
        var repo = library("package start; @SpringBootApplication " + annotation + " class App {}")
                .file("lib/src/main/java/start/InScan.java", "package start; @Service class InScan {}")
                .file("lib/src/main/java/foreign/ForeignClient.java",
                        "package foreign; @FeignClient(\"remote\") interface ForeignClient {}")
                .file("lib/src/main/java/foreign/ForeignBean.java", "package foreign; @Service class ForeignBean {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "InScan.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@EnableFeignClients",
            "@EnableFeignClients(basePackages={\"\", \" \"}, basePackageClasses=int.class)"
    })
    void emptyFeignPackagesAndPrimitiveAnchorsUseTheHomePackage(String annotation) {
        var repo = library("package start; @SpringBootApplication(scanBasePackages=\"none\") "
                + annotation + " class App {}")
                .file("lib/src/main/java/start/HomeClient.java",
                        "package start; @FeignClient(\"home\") interface HomeClient {}")
                .file("lib/src/main/java/foreign/ForeignClient.java",
                        "package foreign; @FeignClient(\"foreign\") interface ForeignClient {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "HomeClient.java");
    }

    @Test
    void explicitFeignClientsIgnorePrimitiveClassLiteralsAndReplacePackageScanning() {
        var repo = library("""
                package start;
                @SpringBootApplication(scanBasePackages="none")
                @EnableFeignClients(clients={int.class, remote.ActiveClient.class}, basePackages="ignored") class App {}
                """)
                .file("lib/src/main/java/remote/ActiveClient.java",
                        "package remote; @FeignClient(\"active\") public interface ActiveClient {}")
                .file("lib/src/main/java/ignored/IgnoredClient.java",
                        "package ignored; @FeignClient(\"ignored\") interface IgnoredClient {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ActiveClient.java");
    }

    @Test
    void composedFeignClassAliasesResolveAtTheirUseSiteWithoutMutatingTheIndex() {
        var repo = library("""
                package start;
                import annotations.LocalClients;
                import remote.Anchor;
                @SpringBootApplication(scanBasePackages="none")
                @LocalClients(basePackageClasses={Anchor.class, int.class}) class App {}
                """)
                .file("lib/src/main/java/annotations/LocalClients.java", """
                        package annotations;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @EnableFeignClients
                        public @interface LocalClients {
                            String CONSTANT = "not an annotation member";
                            String[] unused() default {};
                            @AliasFor(annotation=ComponentScan.class, attribute="value")
                            String[] unrelated() default {"ignored"};
                            @AliasFor(value="clients") Class<?>[] localAlias() default {};
                            @AliasFor(annotation=EnableFeignClients.class, attribute="")
                            Class<?>[] basePackageClasses() default {};
                        }
                        """)
                .file("lib/src/main/java/remote/Anchor.java", "package remote; public class Anchor {}")
                .file("lib/src/main/java/remote/ActiveClient.java",
                        "package remote; @FeignClient(\"active\") interface ActiveClient {}")
                .file("lib/src/main/java/ignored/IgnoredClient.java",
                        "package ignored; @FeignClient(\"ignored\") interface IgnoredClient {}");
        var index = repo.index();

        var services = Discovery.discover(index, "default", null);

        assertThat(services).singleElement().satisfies(service ->
                assertThat(loaded(service)).containsExactlyInAnyOrder(
                        "App.java", "Anchor.java", "ActiveClient.java"));
        var app = index.units().stream().filter(cu -> index.fileOf(cu).getFileName().toString().equals("App.java"))
                .findFirst().orElseThrow();
        assertThat(Ann.attr(Ann.find(app.getClassByName("App").orElseThrow(), "LocalClients").orElseThrow(),
                "basePackageClasses").orElseThrow().asArrayInitializerExpr().getValues())
                .extracting(Object::toString).containsExactly("Anchor.class", "int.class");
    }

    // Pins Discovery.java:323, 363-365: recursive/bodyless helpers terminate while matching varargs overloads supply sources.
    @ParameterizedTest
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ValueSource(strings = {"register(1);", "App.register(1, \"a\", \"b\", \"c\");"})
    void bootSourceHelpersAcceptVarargsAndStopAtRepeatedOrBodylessMethods(String call) {
        var repo = bootLibrary("""
                package start;
                @SpringBootApplication(scanBasePackages="none") class App {
                    public static void main(String[] args) { %s hook(); }
                    static native void hook();
                    static void register() {}
                    static void register(int tag, String a, String b) {}
                    static void register(int tag, int second, int third, String... rest) {}
                    static void register(int tag, String... rest) {
                        new SpringApplicationBuilder().sources(configuration.ScanConfig.class);
                        register(tag);
                    }
                }
                """.formatted(call));

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ScanConfig.java", "ActiveBean.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(new SpringApplicationBuilder()).sources(configuration.ScanConfig.class);",
            "builder().sources(configuration.ScanConfig.class);",
            "builder().profiles(\"test\").sources(configuration.ScanConfig.class);"
    })
    void builderReceiversSupportParenthesesHelperReturnTypesAndFluentScopes(String startup) {
        var repo = bootLibrary("""
                package start;
                @SpringBootApplication(scanBasePackages="none") class App {
                    public static void main(String[] args) { %s }
                    static SpringApplicationBuilder builder() { return null; }
                }
                """.formatted(startup));

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ScanConfig.java", "ActiveBean.java");
    }

    // FINDING: a cast to SpringApplicationBuilder hides its sources' component scans from discovery.
    @Test
    void castBuilderReceiverDoesNotRegisterItsConfigurationScan() {
        var repo = bootLibrary("""
                package start;
                @SpringBootApplication(scanBasePackages="none") class App {
                    public static void main(String[] args) {
                        ((SpringApplicationBuilder) builder()).sources(configuration.ScanConfig.class);
                    }
                    static Object builder() { return new SpringApplicationBuilder(); }
                }
                """);

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ScanConfig.java");
    }

    // Pins Discovery.java:346-352: initialized class arrays register scans; primitive/empty/cast sources do not.
    @ParameterizedTest
    @CsvSource({"int.class, false", "new Class<?>[0], false", "(Class<?>) null, false",
            "new Class<?>[]{configuration.ScanConfig.class}, true"})
    void bootSourcesRegisterScansOnlyForResolvableClassLiterals(String source, boolean registered) {
        var repo = bootLibrary("""
                package start;
                @SpringBootApplication(scanBasePackages="none") class App {
                    public static void main(String[] args) { SpringApplication.run(%s, args); }
                }
                """.formatted(source));

        assertThat(loaded(repo)).containsExactlyInAnyOrder(registered
                ? new String[]{"App.java", "ScanConfig.java", "ActiveBean.java"} : new String[]{"App.java"});
    }

    @Test
    void persistencePackageHelpersIgnoreOtherCollectionsAndNestedMethodReturns() {
        var repo = library("""
                package start;
                import java.util.*;
                @SpringBootApplication(scanBasePackages="none") class App {
                    EntityManagerFactoryBuilder builder() { return null; }
                    @Bean Object factory() {
                        (this.builder()).packages(packages().toArray(new String[0]));
                        (this.builder()).packages(int.class);
                        return null;
                    }
                    List<String> packages() {
                        class Nested { String[] unrelated() { return new String[]{"unused.entities"}; } }
                        List<String> roots = new ArrayList<>();
                        List<String> other = new ArrayList<>();
                        roots.add("");
                        roots.add("registered.entities");
                        roots.addAll(Arrays.asList("registered.entities"));
                        other.add("unused.entities");
                        add("unused.entities");
                        return roots;
                    }
                    void add(String value) {}
                }
                """)
                .file("lib/src/main/java/registered/entities/ActiveEntity.java",
                        "package registered.entities; @Entity class ActiveEntity {}")
                .file("lib/src/main/java/unused/entities/UnusedEntity.java",
                        "package unused.entities; @Entity class UnusedEntity {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ActiveEntity.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@EnableFeignClients(\"remote\")",
            "@MapperScan(\"mappers\")",
            "@EntityScan(\"entities\")",
            "@EnableJpaRepositories(\"domain\")",
            "@EnableMongoRepositories(\"domain\")",
            "@EnableReactiveMongoRepositories(\"domain\")",
            "@EnableAutoConfiguration"
    })
    void registrationWithoutComponentScanningSuppressesTheAllBeanFallback(String annotation) {
        var repo = library("""
                package start;
                %s class App {
                    public static void main(String[] args) { new SpringApplication().run(); }
                }
                """.formatted(annotation))
                .file("lib/src/main/java/foreign/ForeignBean.java", "package foreign; @Service class ForeignBean {}");

        assertThat(loaded(repo)).containsExactly("App.java");
    }

    @Test
    void aNamedBootSourceAloneSuppressesTheAllBeanFallback() {
        var repo = library("""
                package start;
                class App {
                    public static void main(String[] args) {
                        new SpringApplicationBuilder().sources(configuration.PlainSource.class);
                    }
                }
                """)
                .file("lib/src/main/java/configuration/PlainSource.java", "package configuration; public class PlainSource {}")
                .file("lib/src/main/java/foreign/ForeignBean.java", "package foreign; @Service class ForeignBean {}");

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "PlainSource.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"${missing}\"", "Missing.RESOURCES"})
    void unreadableXmlImportLocationsDoNotEnableTheAllBeanFallback(String locations) {
        var repo = library("package start; @SpringBootApplication(scanBasePackages=\"none\") "
                + "@ImportResource(" + locations + ") class App {}")
                .file("lib/src/main/java/foreign/ForeignBean.java", "package foreign; @Service class ForeignBean {}");

        assertThat(loaded(repo)).containsExactly("App.java");
    }

    // Pins Discovery.java:90, 733: service names retain resolved values while remote config files stay inside the root.
    @Test
    void applicationNamesCannotSelectRemoteConfigFilesOutsideTheCheckout() {
        var repo = TempRepo.at(tmp.resolve("client")).app()
                .resource("application.properties", "spring.application.name=../outside\nowner=local\n");
        var remote = TempRepo.at(tmp.resolve("remote"));
        TempRepo.at(tmp).file("outside.properties", "owner=outside\n");
        TempRepo.at(tmp.resolve("outside")).file("application.properties", "owner=outside-folder\n");

        var services = repo.services("default", remote.root());

        assertThat(services).singleElement().satisfies(service -> {
            // FINDING: service name derived from an unsanitised path-like value
            assertThat(service.name()).isEqualTo("../outside");
            assertThat(service.config().get("owner").value()).isEqualTo("local");
        });
    }

    @Test
    void remoteGlobsIgnoreDirectoriesWhoseNamesDoNotMatch() {
        var repo = configClient("locations/team-*");
        var remote = TempRepo.at(tmp.resolve("remote"))
                .file("locations/team-a/orders.properties", "owner=team-a\n")
                .file("locations/other/orders.properties", "owner=other\nother-only=true\n");

        var service = repo.services("default", remote.root()).stream()
                .filter(s -> s.name().equals("orders")).findFirst().orElseThrow();

        assertThat(service.config().get("owner").value()).isEqualTo("team-a");
        assertThat(service.config().get("other-only")).isNull();
    }

    private TempRepo library(String application) {
        return TempRepo.at(tmp.resolve("repo"))
                .file("app/pom.xml", """
                        <project><artifactId>app</artifactId><dependencies>
                        <dependency><artifactId>lib</artifactId></dependency>
                        </dependencies></project>
                        """)
                .file("lib/pom.xml", "<project><artifactId>lib</artifactId></project>")
                .file("app/src/main/java/start/App.java", application)
                .file("app/src/main/resources/application.properties", "spring.application.name=system\n");
    }

    private TempRepo bootLibrary(String application) {
        return library(application)
                .file("lib/src/main/java/configuration/ScanConfig.java",
                        "package configuration; @Configuration @ComponentScan(\"registered\") public class ScanConfig {}")
                .file("lib/src/main/java/registered/ActiveBean.java", "package registered; @Service class ActiveBean {}")
                .file("lib/src/main/java/unused/UnusedBean.java", "package unused; @Service class UnusedBean {}");
    }

    private TempRepo configClient(String searchPath) {
        return TempRepo.at(tmp.resolve("client"))
                .file("orders/src/main/java/orders/App.java", "package orders; @SpringBootApplication class App {}")
                .file("orders/src/main/resources/application.properties", "spring.application.name=orders\nowner=local\n")
                .file("server/src/main/java/server/App.java",
                        "package server; @SpringBootApplication @EnableConfigServer class App {}")
                .file("server/src/main/resources/application.properties",
                        "spring.application.name=server\nspring.cloud.config.server.git.search-paths=" + searchPath + "\n");
    }

    private static List<String> loaded(TempRepo repo) {
        return loaded(repo.service());
    }

    private static List<String> loaded(Service service) {
        return service.units().stream()
                .map(cu -> service.index().fileOf(cu).getFileName().toString()).toList();
    }
}
