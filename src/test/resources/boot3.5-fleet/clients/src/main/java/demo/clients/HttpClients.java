package demo.clients;

@FeignClient(name = "boot3-api")
interface ItemsFeign {
    @GetMapping("/items") Object list();
}

@HttpExchange(url = "http://boot3-api")
interface ItemsExchange {
    @GetExchange("/items") Object list();
}

class Calls {
    RestClient restClient;
    WebClient webClient;
    RestTemplate restTemplate;
    void call() {
        restClient.get().uri("http://boot3-api/items").retrieve();
        webClient.get().uri("http://boot3-api/items");
        restTemplate.getForObject("${api.base-url}/items", Object.class);
    }
}
