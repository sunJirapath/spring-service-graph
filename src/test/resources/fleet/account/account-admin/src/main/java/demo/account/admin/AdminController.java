package demo.account.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController implements AdminApi {

    @Override
    public ResponseEntity<Void> deleteAccount(Long id) {
        return ResponseEntity.noContent().build();
    }
}
