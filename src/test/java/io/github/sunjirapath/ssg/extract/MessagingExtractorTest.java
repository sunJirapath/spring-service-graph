package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingExtractorTest {

    @Test
    void rabbitListenerQueuesAndTemplateRoutingKeys(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class Q { static final String ORDERS = "orders"; }
                class L {
                    @RabbitListener(queues = {Q.ORDERS, "audit"}) void on() {}
                    @RabbitListener(bindings = @QueueBinding(value = @Queue)) void bound() {}
                }
                class P {
                    RabbitTemplate rabbit; AmqpTemplate amqp;
                    void send() {
                        rabbit.convertAndSend("ex", Q.ORDERS, "m");
                        amqp.convertAndSend("direct", "m");
                        amqp.convertAndSend("m");
                    }
                }
                """).facts(new MessagingExtractor());

        assertThat(topics(facts, false)).contains("orders", "audit").hasSize(3); // + unresolved bindings
        assertThat(topics(facts, true)).containsExactlyInAnyOrder("orders", "direct");
    }

    @Test
    void rocketListenerTemplateAndPlainClient(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                import org.apache.rocketmq.common.message.Message;
                @RocketMQMessageListener(topic = "pay", consumerGroup = "g")
                class L {}
                class P {
                    RocketMQTemplate rocket; DefaultMQPushConsumer consumer;
                    void send() throws Exception {
                        rocket.syncSend("order:created", "m");
                        new Message("sms", "tag", new byte[0]);
                        consumer.subscribe("uac", "*");
                    }
                }
                """).facts(new MessagingExtractor());

        assertThat(topics(facts, false)).containsExactlyInAnyOrder("pay", "uac");
        assertThat(topics(facts, true)).containsExactlyInAnyOrder("order", "sms");
    }

    @Test
    void amqpTemplateSendsButOddCallsAndSpringMessagesAreIgnored(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class P {
                    AmqpTemplate amqp; RocketMQTemplate rocket; DefaultMQPushConsumer consumer; Object other;
                    void send(String dynamic) {
                        amqp.convertAndSend("ex", "billing.key", "m");
                        rocket.syncSend(dynamic, "m");
                        rocket.syncSend("plain", "m");
                        new Message("spring", "payload");
                        new Message("one");
                        consumer.subscribe("only-topic");
                        other.subscribe("a", "b");
                    }
                }
                """).facts(new MessagingExtractor());

        assertThat(topics(facts, true)).containsExactly("billing.key", "{?}", "plain");
        assertThat(topics(facts, false)).isEmpty();
    }

    @Test
    void bindingRoutesSendToQueueAndFollowsDeadLetterArguments(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class QueueEnum { static final String CANCEL = "cancel"; static final String TTL = "cancel.ttl"; static final String EX = "ex"; static final String TTL_EX = "ttl.ex"; }
                class Config {
                    @Bean DirectExchange orderDirect() { return ExchangeBuilder.directExchange(QueueEnum.EX).durable(true).build(); }
                    @Bean DirectExchange ttlDirect() { return new DirectExchange(QueueEnum.TTL_EX); }
                    @Bean Queue cancelQueue() { return new Queue(QueueEnum.CANCEL); }
                    @Bean Queue ttlQueue() {
                        return QueueBuilder.durable(QueueEnum.TTL)
                                .withArgument("x-dead-letter-exchange", QueueEnum.EX)
                                .withArgument("x-dead-letter-routing-key", QueueEnum.CANCEL).build();
                    }
                    @Bean Binding cancelBinding(Queue cancelQueue, DirectExchange orderDirect) {
                        return BindingBuilder.bind(cancelQueue).to(orderDirect).with(QueueEnum.CANCEL);
                    }
                    @Bean Binding ttlBinding() { return BindingBuilder.bind(ttlQueue()).to(ttlDirect()).with(QueueEnum.TTL); }
                }
                class P {
                    RabbitTemplate rabbit;
                    void send() { rabbit.convertAndSend(QueueEnum.TTL_EX, QueueEnum.TTL, "m"); }
                }
                """).facts(new MessagingExtractor());

        assertThat(topics(facts, true)).containsExactly("cancel.ttl", "cancel");
    }

    @Test
    void deadLetterBuilderAndMapFormsAndCycles(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class Config {
                    @Bean TopicExchange t() { return new TopicExchange("t"); }
                    @Bean Queue a() { return QueueBuilder.nonDurable("a").deadLetterExchange("t").deadLetterRoutingKey("b").build(); }
                    @Bean Queue b() {
                        Map<String, Object> args = new HashMap<>();
                        args.put("x-dead-letter-exchange", "t");
                        args.put("x-dead-letter-routing-key", "a");
                        args.put("x-message-ttl", 5);
                        return new Queue("b", true, false, false, args);
                    }
                    @Bean Binding ba() { return BindingBuilder.bind(a()).to(t()).with("ka"); }
                    @Bean Binding bb() { return BindingBuilder.bind(b()).to(t()).with("b"); }
                    @Bean Binding other() { return BindingBuilder.bind(a()).to(t()).with("a"); }
                }
                class P {
                    RabbitTemplate rabbit;
                    void send() { rabbit.convertAndSend("t", "ka", "m"); }
                }
                """).facts(new MessagingExtractor());

        // a -> b -> a is a cycle, each queue once
        assertThat(topics(facts, true)).containsExactly("a", "b");
    }

    @Test
    void unmatchedSendsFallBackToTheRoutingKey(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class Config {
                    @Bean DirectExchange e() { return new DirectExchange("e"); }
                    @Bean FanoutExchange f() { return new FanoutExchange("f"); }
                    @Bean Queue q() { return new Queue("q"); }
                    @Bean Queue fq() { return new Queue("fq"); }
                    @Bean Queue unnamed() { return null; }
                    @Bean Queue noArgs() { return new Queue(); }
                    @Bean Queue helped() {
                        helper.durable("x"); helper.put("only-one"); helper.bind();
                        return new Queue("helped");
                    }
                    @Bean Exchange noName() { return null; }
                    @Bean Exchange odd() { other.directExchange("z"); builder.fooExchange(); new HashMap<>(); new FanoutExchange(); return null; }
                    @Bean Binding noTarget() { return BindingBuilder.bind(helped()).with("nt"); }
                    @Bean Binding bound() { return BindingBuilder.bind(q()).to(e()).with("k"); }
                    @Bean Binding keyless() { return BindingBuilder.bind(fq()).to(f()); }
                    @Bean Binding missingQueue() { return BindingBuilder.bind(unknown()).to(e()).with("z"); }
                    @Bean Binding strayExchange() { return BindingBuilder.bind(q()).to(elsewhere()).with("loose"); }
                }
                class P {
                    RabbitTemplate rabbit; String dynamic;
                    void send() {
                        rabbit.convertAndSend("other", "k", "m");
                        rabbit.convertAndSend("e", "k", "m");
                        rabbit.convertAndSend("e", "nobind", "m");
                        rabbit.convertAndSend("f", "fq", "m");
                        rabbit.convertAndSend("e", "z", "m");
                        rabbit.convertAndSend("any", "loose", "m");
                        rabbit.convertAndSend("e", dynamic, "m");
                        rabbit.convertAndSend("k", "m");
                        rabbit.convertAndSend("e", "nt", "m");
                    }
                }
                """).facts(new MessagingExtractor());

        // "other"/k is not bound to exchange e so it names the key; the stray binding's exchange is unknown, so it matches any
        assertThat(topics(facts, true)).containsExactly("k", "q", "nobind", "fq", "z", "q", "{?}", "q", "helped");
    }

    private static TempRepo repo(Path tmp, String source) {
        return TempRepo.at(tmp.resolve("repo")).app().java("x/Messaging.java", source);
    }

    private static List<String> topics(List<Fact> facts, boolean produce) {
        return facts.stream().filter(Fact.TopicUse.class::isInstance).map(Fact.TopicUse.class::cast)
                .filter(t -> t.produce() == produce).map(t -> t.topic().text()).toList();
    }
}
