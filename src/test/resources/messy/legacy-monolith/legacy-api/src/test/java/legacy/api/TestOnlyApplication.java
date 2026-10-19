package legacy.api;

@SpringBootApplication
class TestOnlyApplication {
    @RestController static class TestController {
        @GetMapping("/must-not-exist") Object nope() { return null; }
    }
}
