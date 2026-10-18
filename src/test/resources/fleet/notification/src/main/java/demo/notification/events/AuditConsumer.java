package demo.notification.events;

import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditConsumer {

    @Bean
    public Consumer<String> audit() {
        return msg -> {};
    }
}
