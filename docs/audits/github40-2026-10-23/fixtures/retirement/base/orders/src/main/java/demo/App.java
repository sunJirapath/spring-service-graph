package demo;
@SpringBootApplication @RestController class App { @GetMapping("/orders") public String orders() { return "ok"; } }
