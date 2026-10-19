package events;

class EventHandlers {
    StreamBridge streamBridge;
    KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "${topics.orders:orders.changed}")
    void orders(Object event) {}

    @KafkaListener(topicPattern = "legacy\\..*")
    void legacy(Object event) {}

    @KafkaListener(topics = "#{topicRegistry.dynamicTopic()}")
    void dynamic(Object event) {}

    @KafkaListener(topics = "lonely.listener")
    void lonely(Object event) {}

    void publish(Object event) {
        streamBridge.send("orders-out-0", event);
        kafkaTemplate.send("orphan.produced", event);
    }
}
