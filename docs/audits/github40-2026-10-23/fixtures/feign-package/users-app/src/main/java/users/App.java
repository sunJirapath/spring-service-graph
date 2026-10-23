package users; @SpringBootApplication class App { @RestController static class C { @GetMapping("/users/{id}") String get(@PathVariable String id){ return ""; } } }
