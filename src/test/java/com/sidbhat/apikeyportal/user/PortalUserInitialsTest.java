package com.sidbhat.apikeyportal.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The avatar in the navigation bar is built from these, so it must never come
 * out empty or longer than two letters.
 */
class PortalUserInitialsTest {

    private static String initialsFor(String displayName) {
        return new PortalUser(TestUsers.account(1L, "someone@example.com", displayName,
                UserRole.MEMBER, TestUsers.platformTeam())).getInitials();
    }

    @Test
    void takesTheFirstAndLastInitialOfAName() {
        assertThat(initialsFor("Alex Carter")).isEqualTo("AC");
        assertThat(initialsFor("Jo Okafor")).isEqualTo("JO");
    }

    @Test
    void aSingleWordNameGivesOneLetter() {
        assertThat(initialsFor("Administrator")).isEqualTo("A");
    }

    @Test
    void ignoresMiddleNamesAndStrayWhitespace() {
        assertThat(initialsFor("  Alex  Quinn  Carter  ")).isEqualTo("AC");
    }

    @Test
    void fallsBackToTheEmailWhenThereIsNoName() {
        assertThat(initialsFor(" ")).isEqualTo("S");
    }

    @Test
    void isAlwaysUpperCase() {
        assertThat(initialsFor("alex carter")).isEqualTo("AC");
    }
}
