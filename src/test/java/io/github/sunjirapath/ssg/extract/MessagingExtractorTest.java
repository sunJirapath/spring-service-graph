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

    private static TempRepo repo(Path tmp, String source) {
        return TempRepo.at(tmp.resolve("repo")).app().java("x/Messaging.java", source);
    }

    private static List<String> topics(List<Fact> facts, boolean produce) {
        return facts.stream().filter(Fact.TopicUse.class::isInstance).map(Fact.TopicUse.class::cast)
                .filter(t -> t.produce() == produce).map(t -> t.topic().text()).toList();
    }
}
