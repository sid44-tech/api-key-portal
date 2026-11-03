package io.github.apikeyportal.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SsoHealthIndicatorTest {

    private final SsoService sso = mock(SsoService.class);
    private MockRestServiceServer server;
    private SsoHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        indicator = new SsoHealthIndicator(sso, builder);
    }

    private SsoConfiguration enabled() {
        SsoConfiguration config = new SsoConfiguration("Contoso", "https://login.example.com/tid/v2.0", "client", "s");
        config.setEnabled(true);
        return config;
    }

    @Test
    void withSsoOffThereIsNothingToCheckAndItSaysSo() {
        when(sso.find()).thenReturn(Optional.empty());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("enabled", false);
        server.verify();
    }

    @Test
    void aReachableIssuerIsUp() {
        when(sso.find()).thenReturn(Optional.of(enabled()));
        server.expect(requestTo("https://login.example.com/tid/v2.0/.well-known/openid-configuration"))
                .andRespond(withSuccess("{\"issuer\":\"https://login.example.com/tid/v2.0\"}", MediaType.APPLICATION_JSON));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("issuer", "https://login.example.com/tid/v2.0");
    }

    @Test
    void anIssuerThatCannotBeReadIsDownWithTheReason() {
        when(sso.find()).thenReturn(Optional.of(enabled()));
        server.expect(requestTo("https://login.example.com/tid/v2.0/.well-known/openid-configuration"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(health.getDetails().get("error"))).contains("502");
    }
}
