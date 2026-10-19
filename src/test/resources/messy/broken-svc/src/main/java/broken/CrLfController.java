package broken;

@RestController
class CrLfController {
    @GetMapping("/crlf") Object crlf() { return null; }
}
