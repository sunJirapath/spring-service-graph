package modern;

class ModernOutbound {
    Object restClient = RestClient.builder().baseUrl("http://legacy-api").build();

    Object load(long id) {
        return restClient.get().uri("/legacy/orders/{id}").retrieve();
    }
}
