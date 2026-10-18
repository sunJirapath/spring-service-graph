package demo.account.web.api;

import javax.annotation.processing.Generated;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen")
@Validated
public interface AccountsApi {

    @GetMapping(value = "/accounts/{id}", produces = "application/json")
    default ResponseEntity<String> getAccount(@PathVariable("id") Long id) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @RequestMapping(method = RequestMethod.POST, value = "/accounts")
    default ResponseEntity<String> createAccount(@RequestBody String body) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }
}
