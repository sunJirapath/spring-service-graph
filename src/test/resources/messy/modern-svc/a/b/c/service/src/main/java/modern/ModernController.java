package modern;

@RestController
@RequestMapping("/modern")
public class ModernController {
    @GetMapping("/items/{id}")
    public Item item(long id) { return new Item(id, "ok"); }
}

record Item(long id, String state) {}
