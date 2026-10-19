package modern;

class ModernEvents {
    static final String CHANGED = "${topics.orders:orders.changed}";
    KafkaTemplate<String, Object> kafkaTemplate;

    void changed(Object payload) {
        kafkaTemplate.send(CHANGED, payload);
    }
}
