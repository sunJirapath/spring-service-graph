package demo.account.web;

import demo.account.events.AccountEvents;
import demo.account.web.api.AccountsApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api")
public class AccountController implements AccountsApi {

    private final AccountEvents events;

    public AccountController(AccountEvents events) {
        this.events = events;
    }

    @Override
    public ResponseEntity<String> getAccount(@PathVariable("id") Long id) {
        return ResponseEntity.ok("{}");
    }

    @Override
    public ResponseEntity<String> createAccount(@RequestBody String body) {
        events.accountCreated(body);
        return ResponseEntity.ok(body);
    }
}
