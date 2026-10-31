package com.sidbhat.apikeyportal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class NavigationAdviceTest {

    private final NavigationAdvice advice = new NavigationAdvice();

    private String activeNavFor(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return advice.activeNav(request);
    }

    @Test
    void marksTheSectionAPageBelongsTo() {
        assertThat(activeNavFor("/keys")).isEqualTo("keys");
        assertThat(activeNavFor("/people")).isEqualTo("people");
        assertThat(activeNavFor("/groups")).isEqualTo("groups");
    }

    @Test
    void aPageDeeperInASectionStillHighlightsThatSection() {
        assertThat(activeNavFor("/keys/3/edit")).isEqualTo("keys");
        assertThat(activeNavFor("/invitations/new")).isEqualTo("invitations");
        assertThat(activeNavFor("/keys/guided/github")).isEqualTo("keys");
    }

    @Test
    void theRootMatchesNothingRatherThanBlowingUp() {
        assertThat(activeNavFor("/")).isEmpty();
    }

    @Test
    void honoursAContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/portal/keys");
        request.setRequestURI("/portal/keys");
        request.setContextPath("/portal");

        assertThat(advice.activeNav(request)).isEqualTo("keys");
    }
}
