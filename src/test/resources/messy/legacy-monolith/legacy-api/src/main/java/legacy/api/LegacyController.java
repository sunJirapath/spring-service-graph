package legacy.api;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/legacy")
public class LegacyController {
    @org.springframework.web.bind.annotation.GetMapping("/orders/{id}")
    public Object order(long id) { return null; }

    @RequestMapping(method = RequestMethod.POST, value = "/x")
    public void oldPost() {}
}
