package io.github.apikeyportal.integration.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.apikeyportal.integration.ProvisioningException;
import io.github.apikeyportal.integration.cloudflare.CloudflareClient.CreatedToken;
import io.github.apikeyportal.integration.cloudflare.CloudflareClient.TemplateToken;

class CloudflareClientTest {

    private static final String API = "https://cloudflare.test/v4";

    private MockRestServiceServer server;
    private CloudflareClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CloudflareClient(builder, new CloudflareProperties(API));
    }

    @Test
    void verifyAcceptsAnActiveToken() {
        server.expect(requestTo(API + "/user/tokens/verify"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer cf-admin"))
                .andRespond(withSuccess("{\"result\": {\"id\": \"abc\", \"status\": \"active\"}}",
                        MediaType.APPLICATION_JSON));

        client.verifyToken("cf-admin");
    }

    @Test
    void verifyRefusesADisabledToken() {
        server.expect(requestTo(API + "/user/tokens/verify"))
                .andRespond(withSuccess("{\"result\": {\"id\": \"abc\", \"status\": \"disabled\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.verifyToken("cf-admin"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("disabled, not active");
    }

    @Test
    void mintsATokenCarryingTheTemplatesPoliciesVerbatim() {
        server.expect(requestTo(API + "/user/tokens/tmpl-1"))
                .andRespond(withSuccess("""
                        {"result": {"id": "tmpl-1", "name": "portal-template",
                          "policies": [{"effect": "allow",
                            "resources": {"com.cloudflare.api.account.zone.z1": "*"},
                            "permission_groups": [{"id": "dns-write"}]}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/user/tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer cf-admin"))
                .andExpect(jsonPath("$.name").value("Reporting service"))
                .andExpect(jsonPath("$.policies[0].permission_groups[0].id").value("dns-write"))
                .andExpect(jsonPath("$.expires_on").value("2027-05-01T00:00:00Z"))
                .andRespond(withSuccess("{\"result\": {\"id\": \"new-1\", \"value\": \"cf-new-secret\"}}",
                        MediaType.APPLICATION_JSON));

        TemplateToken template = client.templateToken("cf-admin", "tmpl-1");
        CreatedToken token = client.createToken("cf-admin", "Reporting service", template.policies(),
                Instant.parse("2027-05-01T00:00:00Z"));

        assertThat(template.name()).isEqualTo("portal-template");
        assertThat(token.value()).isEqualTo("cf-new-secret");
        assertThat(token.id()).isEqualTo("new-1");
    }

    @Test
    void aTemplateWithNoPoliciesIsRefusedAtConnectTimeNotFirstUse() {
        server.expect(requestTo(API + "/user/tokens/tmpl-1"))
                .andRespond(withSuccess("{\"result\": {\"id\": \"tmpl-1\", \"name\": \"empty\", \"policies\": []}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.templateToken("cf-admin", "tmpl-1"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("no policies to copy");
    }

    @Test
    void cloudflaresOwnErrorMessageIsPassedAlong() {
        server.expect(requestTo(API + "/user/tokens"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\": false, \"errors\": [{\"code\": 9109, "
                                + "\"message\": \"Unauthorized to access requested resource\"}]}"));

        assertThatThrownBy(() -> client.createToken("cf-admin", "x", List.of(Map.of("effect", "allow")), null))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Unauthorized to access requested resource");
    }
}
