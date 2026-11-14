package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InboundMessagingBranchGap2Test {
    @TempDir Path tmp;

    @Test
    void routerNestingRequiresChildArgumentAndHandlesEmptyAndMethodPredicates() {
        var facts = TempRepo.at(tmp.resolve("router")).app().java("x/Routes.java", """
                package x;
                import static org.springframework.web.reactive.function.server.RouterFunctions.*;
                import static org.springframework.web.reactive.function.server.RequestPredicates.*;
                @Configuration class Routes {
                    @Bean RouterFunction<?> routes() {
                        return nest(path("/ignored"), "extra", route(GET("/wrong-arity"), this::handle))
                            .and(nest(route(GET("/child-not-second"), this::handle), path("/unused")))
                            .and(route(path("/api").and(method(HttpMethod.POST)), this::handle))
                            .and(route(method(HttpMethod.GET).and(path("/also")), this::handle))
                            .and(nest(path("/x", "extra"), route(GET("/multi-path"), this::handle)))
                            .and(nest(path("/x"), route(GET("/wrong-child"), this::handle)).and(route(GET("/plain"), this::handle)));
                    }
                    Object handle(ServerRequest r) { return null; }
                }
                """).facts(new InboundHttpExtractor());

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).method() + " " + ((Fact.Endpoint) f).path().text())
                .containsExactlyInAnyOrder("GET /wrong-arity", "GET /child-not-second", "POST /api", "GET /also",
                        "GET /{?}/multi-path", "GET /x/wrong-child", "GET /plain");
        assertThat(facts).filteredOn(f -> f instanceof Fact.Endpoint e && e.path().text().contains("multi-path"))
                .singleElement().isInstanceOfSatisfying(Fact.Endpoint.class, e -> assertThat(e.path().confidence()).isEqualTo(Confidence.UNRESOLVED));
    }

    @Test
    void packagePatternPrefixRequiresRestControllerAndMatchingPackage() {
        var facts = TempRepo.at(tmp.resolve("prefix")).app()
                .java("x/Config.java", """
                        package x;
                        @Configuration class Config { void setup() { Object api = new Api("/admin", "**.controller.admin.**"); }
                            void configure(Object c, Object prefixes) { c.setPathPrefixes(prefixes); }
                        }
                        class Api { Api(String p, String pkg) {} }
                        """)
                .java("x/controller/admin/Selected.java", "package x.controller.admin; @RestController class Selected { @GetMapping(\"/ok\") void ok() {} }")
                .java("x/controller/admin/Plain.java", "package x.controller.admin; @Controller class Plain { @GetMapping(\"/plain\") void plain() {} }")
                .java("x/controller/other/Other.java", "package x.controller.other; @RestController class Other { @GetMapping(\"/other\") void other() {} }")
                .facts(new InboundHttpExtractor());

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).path().text())
                .containsExactlyInAnyOrder("/admin/ok", "/plain", "/other");
    }

    @Test
    void rabbitSendArityAndBindingExchangeMismatchKeepTheRoutingKey() {
        var facts = TempRepo.at(tmp.resolve("rabbit")).app().java("x/Rabbit.java", """
                package x;
                class Rabbit {
                    RabbitTemplate rabbit;
                    AmqpTemplate amqp;
                    @Bean Queue q() { return new Queue("bound"); }
                    @Bean DirectExchange exchange() { return new DirectExchange("orders"); }
                    @Bean Binding binding() { return BindingBuilder.bind(q()).to(exchange()).with("key").custom(1); }
                    @Bean Binding missingQueueBinding() { return BindingBuilder.bind(unknown()).to(exchange()).with("orphan"); }
                    @Bean Queue unknown() { return null; }
                    @Bean Queue builderQueue() { return QueueBuilder.durable("built").custom(1).build(); }
                    void send() {
                        amqp.send("amqp-key", "body");
                        rabbit.convertAndSend("other", "key", "body", new MessagePostProcessor());
                        rabbit.convertAndSend("orders", "key", "body", new MessagePostProcessor());
                        rabbit.convertAndSend("direct", "body", new MessagePostProcessor(), new CorrelationData());
                        rabbit.convertAndSend("orders", "five", "body", new MessagePostProcessor(), new CorrelationData());
                        rabbit.send("orders", "six", "body", 1, 2, 3);
                    }
                }
                """).facts(new MessagingExtractor());

        assertThat(facts).filteredOn(f -> f instanceof Fact.TopicUse t && t.produce())
                .extracting(f -> ((Fact.TopicUse) f).topic().text())
                .containsExactly("amqp-key", "key", "bound", "direct", "five", "{?}");
    }
}
