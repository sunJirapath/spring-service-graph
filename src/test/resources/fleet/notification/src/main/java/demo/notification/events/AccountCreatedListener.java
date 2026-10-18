package demo.notification.events;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AccountCreatedListener {

    @KafkaListener(topics = "${topics.account-created:account.created}", groupId = "notification")
    public void onAccountCreated(String payload) {}
}
