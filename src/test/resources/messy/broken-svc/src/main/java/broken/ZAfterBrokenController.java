package broken;

@RestController
class ZAfterBrokenController {
    @GetMapping("/after-broken") Object alive() { return null; }
}
