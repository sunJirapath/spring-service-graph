package demo.account.events;

import demo.account.api.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccountEvents {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public AccountEvents(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    void accountCreated(String payload) {
        kafkaTemplate.send(Topics.ACCOUNT_CREATED, payload);
    }
}
