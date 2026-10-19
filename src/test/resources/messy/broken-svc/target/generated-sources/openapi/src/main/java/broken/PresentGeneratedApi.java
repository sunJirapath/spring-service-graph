package broken;

interface PresentGeneratedApi {
    @GetMapping("/generated/{id}") Object generated(long id);
}
