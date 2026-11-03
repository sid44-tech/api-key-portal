package io.github.apikeyportal.guided;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.apikeyportal.guided.StripeClient.AccountInfo;
import io.github.apikeyportal.guided.StripeClient.StripeProperties;
import io.github.apikeyportal.integration.ProvisioningException;

class StripeClientTest {

    private static final String API = "https://stripe.test";

    private MockRestServiceServer server;
    private StripeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new StripeClient(builder, new StripeProperties(API));
    }

    @Test
    void readsTheAccountAndItsModeWhenTheKeyMay() {
        server.expect(requestTo(API + "/v1/account"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer rk_live_abc"))
                .andRespond(withSuccess("""
                        {"id": "acct_123", "livemode": true,
                         "business_profile": {"name": "Acme Ltd"}}
                        """, MediaType.APPLICATION_JSON));

        AccountInfo info = client.verifyKey("rk_live_abc");

        assertThat(info.id()).isEqualTo("acct_123");
        assertThat(info.livemode()).isTrue();
        assertThat(info.businessProfile().name()).isEqualTo("Acme Ltd");
    }

    @Test
    void aRestrictedKeyWithoutAccountPermissionStillPasses() {
        // 403 means Stripe knows the key but it may not read the account - which
        // is exactly what a well-scoped restricted key looks like
        server.expect(requestTo(API + "/v1/account"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        AccountInfo info = client.verifyKey("rk_live_narrow");

        assertThat(info.id()).isNull();
        assertThat(info.livemode()).isNull();
    }

    @Test
    void a401IsARealRejection() {
        server.expect(requestTo(API + "/v1/account"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.verifyKey("rk_wrong"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("did not accept the key")
                .hasMessageContaining("revoked or rolled");
    }

    @Test
    void otherRefusalsCarryTheStatus() {
        server.expect(requestTo(API + "/v1/account"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.verifyKey("rk_live_abc"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("HTTP 429");
    }
}
