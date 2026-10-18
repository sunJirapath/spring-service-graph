package demo.account.data;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    private Long id;

    private String email;

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }
}
