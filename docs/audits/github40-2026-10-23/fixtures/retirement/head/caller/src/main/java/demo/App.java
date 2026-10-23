package demo;
@SpringBootApplication @RestController class App { RestTemplate rest; @GetMapping("/entry") String entry() { return "retired"; } }
