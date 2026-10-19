package io.github.sunjirapath.ssg.link;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathMatchEdgeCasesTest {

    @Test
    void literalPathsMatchWhenEqual() {
        assertThat(PathMatch.matches("/orders/active", "/orders/active")).isTrue();
    }

    @Test
    void literalPathsDoNotMatchWhenDifferent() {
        assertThat(PathMatch.matches("/orders/active", "/orders/inactive")).isFalse();
    }

    @Test
    void pathVariableOnEitherSideMatchesOneSegment() {
        assertThat(PathMatch.matches("/orders/{orderId}", "/orders/42")).isTrue();
        assertThat(PathMatch.matches("/orders/42", "/orders/{id}")).isTrue();
    }

    @Test
    void doubleStarAtStartMatchesTheRest() {
        assertThat(PathMatch.matches("/**", "/v1/orders/42")).isTrue();
        assertThat(PathMatch.matches("/v1/orders/42", "/**")).isTrue();
        assertThat(PathMatch.matches("/**/status", "/v1/orders/status")).isTrue();
        assertThat(PathMatch.matches("/**/status", "/v1/orders/other")).isFalse();
    }

    @Test
    void doubleStarInMiddleMatchesRemainingSegments() {
        assertThat(PathMatch.matches("/api/**/status", "/api/orders/42/status")).isTrue();
        assertThat(PathMatch.matches("/api/orders/42/status", "/api/**/status")).isTrue();
        assertThat(PathMatch.matches("/api/**/status", "/api/orders/42/other")).isFalse();
    }

    @Test
    void doubleStarAtEndMatchesZeroOrMoreSegments() {
        assertThat(PathMatch.matches("/api/**", "/api")).isTrue();
        assertThat(PathMatch.matches("/api", "/api/**")).isTrue();
        assertThat(PathMatch.matches("/api/**", "/api/orders/42")).isTrue();
    }

    @Test
    void trailingUnknownMatchesOneOrMoreRemainingSegments() {
        assertThat(PathMatch.matches("/owners/{?}", "/owners/1/pets/2")).isTrue();
        assertThat(PathMatch.matches("/owners/{?}", "/owners")).isFalse();
    }

    @Test
    void unknownInMiddleMatchesExactlyOneSegment() {
        assertThat(PathMatch.matches("/owners/{?}/pets", "/owners/1/pets")).isTrue();
        assertThat(PathMatch.matches("/owners/{?}/pets", "/owners/1/2/pets")).isFalse();
    }

    @Test
    void partialTemplateMatchesWithinSegment() {
        assertThat(PathMatch.matches("/accounts/acc-{id}", "/accounts/acc-123")).isTrue();
        assertThat(PathMatch.matches("/accounts/acc-{id}", "/accounts/user-123")).isFalse();
        assertThat(PathMatch.matches("/accounts/acc-{id}", "/accounts/user-{name}")).isFalse();
    }

    @Test
    void starMatchesOneWholeSegment() {
        assertThat(PathMatch.matches("/files/*/meta", "/files/a/meta")).isTrue();
        assertThat(PathMatch.matches("/files/*/meta", "/files/a/b/meta")).isFalse();
    }

    @Test
    void emptyAndRootPathsAreEquivalent() {
        assertThat(PathMatch.matches("", "/")).isTrue();
        assertThat(PathMatch.matches("/", "")).isTrue();
    }

    @Test
    void differentSegmentCountsDoNotMatchWithoutCatchAll() {
        assertThat(PathMatch.matches("/a/b", "/a/b/c")).isFalse();
        assertThat(PathMatch.matches("/a/b/c", "/a/b")).isFalse();
    }
}
