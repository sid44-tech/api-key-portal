package io.github.apikeyportal.integration.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.apikeyportal.integration.ProvisioningException;
import io.github.apikeyportal.integration.gitlab.GitLabClient.CreatedToken;

class GitLabClientTest {

    private static final String BASE = "https://gitlab.test";

    private MockRestServiceServer server;
    private GitLabClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitLabClient(builder);
    }

    @Test
    void aProjectPathGoesIntoTheUrlAsOneEncodedSegment() {
        server.expect(requestTo(BASE + "/api/v4/projects/my-group%2Fmy-app"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("PRIVATE-TOKEN", "glpat-admin"))
                .andRespond(withSuccess("{\"id\": 42}", MediaType.APPLICATION_JSON));

        client.verifyProjectAccess(BASE + "/", "glpat-admin", "my-group/my-app");
    }

    @Test
    void mintsAMaintainerApiTokenWithTheRequestedExpiry() {
        server.expect(requestTo(BASE + "/api/v4/projects/42/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PRIVATE-TOKEN", "glpat-admin"))
                .andExpect(jsonPath("$.name").value("Reporting service"))
                .andExpect(jsonPath("$.scopes[0]").value("api"))
                .andExpect(jsonPath("$.access_level").value(40))
                .andExpect(jsonPath("$.expires_at").value("2027-05-01"))
                .andRespond(withSuccess("""
                        {"id": 99, "token": "glpat-new-secret", "expires_at": "2027-05-01"}
                        """, MediaType.APPLICATION_JSON));

        CreatedToken token = client.createProjectAccessToken(BASE, "glpat-admin", "42",
                "Reporting service", LocalDate.of(2027, 5, 1));

        assertThat(token.token()).isEqualTo("glpat-new-secret");
        assertThat(token.id()).isEqualTo(99L);
        assertThat(token.expiresAt()).isEqualTo("2027-05-01");
    }

    @Test
    void gitLabsOwnErrorMessageIsPassedAlong() {
        server.expect(requestTo(BASE + "/api/v4/projects/42/access_tokens"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\": \"401 Unauthorized\"}"));

        assertThatThrownBy(() -> client.createProjectAccessToken(BASE, "glpat-wrong", "42", "x",
                LocalDate.of(2027, 5, 1)))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("401 Unauthorized");
    }

    @Test
    void aResponseWithoutATokenValueIsAnError() {
        server.expect(requestTo(BASE + "/api/v4/projects/42/access_tokens"))
                .andRespond(withSuccess("{\"id\": 99}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createProjectAccessToken(BASE, "glpat-admin", "42", "x",
                LocalDate.of(2027, 5, 1)))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("did not include the token value");
    }

    @Test
    void rotatesAnExistingTokenInPlace() {
        server.expect(requestTo(BASE + "/api/v4/projects/42/access_tokens/99/rotate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PRIVATE-TOKEN", "glpat-admin"))
                .andExpect(jsonPath("$.expires_at").value("2027-05-01"))
                .andRespond(withSuccess("""
                        {"id": 100, "token": "glpat-rotated", "expires_at": "2027-05-01"}
                        """, MediaType.APPLICATION_JSON));

        CreatedToken rotated = client.rotateProjectAccessToken(BASE, "glpat-admin", "42", "99",
                LocalDate.of(2027, 5, 1));

        // GitLab issues a new id along with the new value; the portal must
        // follow it or the next rotation targets a token that no longer exists
        assertThat(rotated.id()).isEqualTo(100L);
        assertThat(rotated.token()).isEqualTo("glpat-rotated");
    }

    @Test
    void aRotateResponseWithoutATokenValueIsAnError() {
        server.expect(requestTo(BASE + "/api/v4/projects/42/access_tokens/99/rotate"))
                .andRespond(withSuccess("{\"id\": 100}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.rotateProjectAccessToken(BASE, "glpat-admin", "42", "99",
                LocalDate.of(2027, 5, 1)))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("did not include the new token value");
    }
}
