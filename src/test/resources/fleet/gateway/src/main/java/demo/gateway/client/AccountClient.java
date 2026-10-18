package demo.gateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(name = "account")
public interface AccountClient {

    @GetMapping("/api/accounts/{id}")
    AccountDto getAccount(@PathVariable("id") long id);

    @PostMapping("/api/accounts/{id}/close")
    void closeAccount(@PathVariable("id") long id);

    record AccountDto(long id, String email) {}
}
