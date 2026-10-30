package com.sidbhat.apikeyportal.guided;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sidbhat.apikeyportal.guided.GitHubClient.TokenInfo;
import com.sidbhat.apikeyportal.integration.ProvisioningException;

class GitHubClientTest {

    private static final String API = "https://github.test";

    private MockRestServiceServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubClient(builder, new GitHubClient.GitHubProperties(API));
    }

    @Test
    void verifiesTokenAndReadsExpiryAndScopesFromHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("github-authentication-token-expiration", "2026-12-31 08:00:00 UTC");
        headers.set("x-oauth-scopes", "repo, read:org");
        server.expect(requestTo(API + "/user"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer ghp_abc"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess("{\"login\":\"sidbhat\"}", MediaType.APPLICATION_JSON).headers(headers));

        TokenInfo info = client.verifyToken("ghp_abc");

        assertThat(info.login()).isEqualTo("sidbhat");
        assertThat(info.expiresOn()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(info.scopes()).isEqualTo("repo, read:org");
        server.verify();
    }

    @Test
    void handlesTokensWithoutExpiryOrScopes() {
        server.expect(requestTo(API + "/user"))
                .andRespond(withSuccess("{\"login\":\"sidbhat\"}", MediaType.APPLICATION_JSON));

        TokenInfo info = client.verifyToken("ghp_abc");

        assertThat(info.expiresOn()).isNull();
        assertThat(info.scopes()).isNull();
    }

    @Test
    void parsesIsoExpirationHeader() {
        assertThat(GitHubClient.parseExpiration("2026-12-31T08:00:00Z")).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void ignoresUnparseableExpirationHeader() {
        assertThat(GitHubClient.parseExpiration("soon")).isNull();
    }

    @Test
    void translatesRejectedTokenIntoReadableMessage() {
        server.expect(requestTo(API + "/user"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Bad credentials\"}"));

        assertThatThrownBy(() -> client.verifyToken("ghp_wrong"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("GitHub did not accept the token")
                .hasMessageContaining("401");
    }
}
