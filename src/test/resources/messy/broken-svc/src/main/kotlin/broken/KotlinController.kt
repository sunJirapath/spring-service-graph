package broken

@RestController
class KotlinController {
    @GetMapping("/kotlin") fun ignored() = "ignored"
}
