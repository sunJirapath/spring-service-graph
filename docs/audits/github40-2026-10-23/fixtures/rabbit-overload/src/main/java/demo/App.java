package demo;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessagePostProcessor;
@SpringBootApplication class App {}
class Sender {
  RabbitTemplate rabbitTemplate;
  void publish(MessagePostProcessor postProcessor) {
    rabbitTemplate.convertAndSend("orders.key", "payload", postProcessor);
  }
}
