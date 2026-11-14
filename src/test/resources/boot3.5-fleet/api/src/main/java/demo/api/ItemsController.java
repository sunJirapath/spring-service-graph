package demo.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/items")
class ItemsController {
    @GetMapping
    Object list(@Valid ItemQuery query) { return null; }
}

@jakarta.ws.rs.Path("/jaxrs")
class JakartaResource {
    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/health")
    Object health() { return null; }
}

record ItemQuery(@NotBlank String name) {}
