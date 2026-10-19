package legacy.admin;

@RestController
class AdminController {
    @GetMapping("/admin/health") Object health() { return null; }
}
