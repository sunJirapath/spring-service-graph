package demo.gateway.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange(url = "${clients.notification.base-url}")
public interface NotificationClient {

    @PostExchange("/notifications")
    void send(@RequestBody String message);
}
