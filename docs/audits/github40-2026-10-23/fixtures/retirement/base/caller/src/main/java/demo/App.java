package demo;
@SpringBootApplication @RestController class App { RestTemplate rest; @GetMapping("/entry") String entry() { return rest.getForObject("http://orders/orders", String.class); } }
