package io.github.sunjirapath.ssg.link;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkTest {

    @Test
    void pathTemplatesMatchSegmentBySegment() {
        assertThat(PathMatch.matches("/api/accounts/{id}", "/api/accounts/{accountId}")).isTrue();
        assertThat(PathMatch.matches("/api/accounts/42", "/api/accounts/{id}")).isTrue();
        assertThat(PathMatch.matches("/api/accounts/{id}/close", "/api/accounts/{id}")).isFalse();
        assertThat(PathMatch.matches("/**", "/api/accounts")).isTrue();
        assertThat(PathMatch.matches("/owners/{?}", "/owners/{ownerId}/pets")).isTrue();
        assertThat(PathMatch.matches("/owners/acc-{id}", "/owners/acc-1")).isTrue();
        assertThat(PathMatch.matches("/a", "/b")).isFalse();
    }

    @Test
    void urlParsingStripsCredentialsPortAndQuery() {
        var u = Linker.parse("http://user:pw@orders.svc:8080/api/o?x=1#f");
        assertThat(u.host()).isEqualTo("orders.svc");
        assertThat(u.path()).isEqualTo("/api/o");
        assertThat(Linker.parse("lb://customers").path()).isEqualTo("/");
        assertThat(Linker.parse("{?}/reports/{?}").host()).isNull();
        assertThat(Linker.parse("{?}/reports/{?}").path()).isEqualTo("{?}/reports/{?}");
    }
}
