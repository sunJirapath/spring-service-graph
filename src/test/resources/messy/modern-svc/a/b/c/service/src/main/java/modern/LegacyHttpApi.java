package modern;

@HttpExchange("http://legacy-api")
interface LegacyHttpApi {
    @GetExchange("/legacy/orders/{id}")
    Object order(long id);
}
