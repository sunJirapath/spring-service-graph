package broken;
@SpringBootApplication class TestApplication {
    @RestController static class Controller { @GetMapping("/test-only") void ignored() {} }
}
