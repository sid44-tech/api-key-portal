package com.sidbhat.apikeyportal.integration.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedApplication;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedSecret;

class AzureGraphClientTest {

    private static final String LOGIN = "https://login.test";
    private static final String GRAPH = "https://graph.test";

    private MockRestServiceServer server;
    private AzureGraphClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AzureGraphClient(builder, new AzureAdProperties(LOGIN, GRAPH));
    }

    @Test
    void acquiresTokenWithClientCredentials() {
        server.expect(requestTo(LOGIN + "/my-tenant/oauth2/v2.0/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(Matchers.containsString("grant_type=client_credentials")))
                .andExpect(content().string(Matchers.containsString("client_id=client-1")))
                .andExpect(content().string(Matchers.containsString("client_secret=s3cret")))
                .andRespond(withSuccess("{\"token_type\":\"Bearer\",\"access_token\":\"tok-123\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.acquireToken("my-tenant", "client-1", "s3cret")).isEqualTo("tok-123");
        server.verify();
    }

    @Test
    void translatesTokenErrorIntoReadableMessage() {
        server.expect(requestTo(LOGIN + "/my-tenant/oauth2/v2.0/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client secret provided.\"}"));

        assertThatThrownBy(() -> client.acquireToken("my-tenant", "client-1", "wrong"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Azure AD rejected the credentials")
                .hasMessageContaining("401")
                .hasMessageContaining("Invalid client secret");
    }

    @Test
    void createsSingleTenantApplication() {
        server.expect(requestTo(GRAPH + "/v1.0/applications"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andExpect(jsonPath("$.displayName").value("Reporting service"))
                .andExpect(jsonPath("$.signInAudience").value("AzureADMyOrg"))
                .andRespond(withSuccess("{\"id\":\"obj-1\",\"appId\":\"app-1\",\"displayName\":\"Reporting service\"}",
                        MediaType.APPLICATION_JSON));

        CreatedApplication created = client.createApplication("tok-123", "Reporting service");

        assertThat(created.objectId()).isEqualTo("obj-1");
        assertThat(created.clientId()).isEqualTo("app-1");
        server.verify();
    }

    @Test
    void createsServicePrincipalForApplication() {
        server.expect(requestTo(GRAPH + "/v1.0/servicePrincipals"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andExpect(jsonPath("$.appId").value("app-1"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"sp-1\",\"appId\":\"app-1\"}"));

        client.createServicePrincipal("tok-123", "app-1");
        server.verify();
    }

    @Test
    void addsPasswordAndReturnsSecretShownOnce() {
        Instant end = Instant.parse("2027-04-27T12:00:00Z");
        server.expect(requestTo(GRAPH + "/v1.0/applications/obj-1/addPassword"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andExpect(jsonPath("$.passwordCredential.displayName").value("Reporting service"))
                .andExpect(jsonPath("$.passwordCredential.endDateTime").value("2027-04-27T12:00:00Z"))
                .andRespond(withSuccess(
                        "{\"secretText\":\"generated-secret-value\",\"endDateTime\":\"2027-04-27T12:00:00Z\",\"keyId\":\"k-1\"}",
                        MediaType.APPLICATION_JSON));

        CreatedSecret secret = client.addPassword("tok-123", "obj-1", "Reporting service", end);

        assertThat(secret.secret()).isEqualTo("generated-secret-value");
        assertThat(secret.endDateTime()).isEqualTo("2027-04-27T12:00:00Z");
        server.verify();
    }

    @Test
    void deletesApplication() {
        server.expect(requestTo(GRAPH + "/v1.0/applications/obj-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.deleteApplication("tok-123", "obj-1");
        server.verify();
    }

    @Test
    void translatesGraphErrorIntoReadableMessage() {
        server.expect(requestTo(GRAPH + "/v1.0/applications"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"Authorization_RequestDenied\",\"message\":\"Insufficient privileges to complete the operation.\"}}"));

        assertThatThrownBy(() -> client.createApplication("tok-123", "Reporting service"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not create the app registration")
                .hasMessageContaining("403")
                .hasMessageContaining("Insufficient privileges");
    }

    @Test
    void failsWhenSecretMissingFromResponse() {
        server.expect(requestTo(GRAPH + "/v1.0/applications/obj-1/addPassword"))
                .andRespond(withSuccess("{\"keyId\":\"k-1\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.addPassword("tok-123", "obj-1", "x", Instant.now()))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("unexpected response");
    }

    @Test
    void errorWithoutParsableBodyStillProducesMessage() {
        server.expect(requestTo(LOGIN + "/t/oauth2/v2.0/token"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.acquireToken("t", "c", "s"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("(HTTP 400)");
    }
}
