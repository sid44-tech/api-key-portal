package io.github.apikeyportal.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.apikeyportal.alerts.AlertCard.Fact;
import io.github.apikeyportal.alerts.AlertCard.Severity;

class WebhookClientTest {

    private static final String TEAMS_HOOK = "https://prod-12.westus.logic.azure.com/workflows/abc/triggers/manual/paths/invoke";
    private static final String SLACK_HOOK = "https://hooks.slack.com/services/T000/B000/XXXX";

    private MockRestServiceServer server;
    private WebhookClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WebhookClient(builder);
    }

    private static AlertCard card() {
        return new AlertCard(Severity.WARNING, "Key expires in 7 days: GitHub / Deploy token",
                "The one week reminder.", List.of(new Fact("Owner", "Alex Carter")), "Open the key",
                "https://keys.example.com/keys/42");
    }

    @Test
    void teamsGetsAnAdaptiveCardInTheEnvelopeItExpects() {
        server.expect(requestTo(TEAMS_HOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.attachments[0].contentType").value("application/vnd.microsoft.card.adaptive"))
                .andExpect(jsonPath("$.attachments[0].content.type").value("AdaptiveCard"))
                .andExpect(jsonPath("$.attachments[0].content.body[0].text").value("Key expires in 7 days: GitHub / Deploy token"))
                .andExpect(jsonPath("$.attachments[0].content.body[0].color").value("Warning"))
                .andExpect(jsonPath("$.attachments[0].content.body[2].facts[0].title").value("Owner"))
                .andExpect(jsonPath("$.attachments[0].content.actions[0].url").value("https://keys.example.com/keys/42"))
                // Workflows answer 202 with no body; the client must not insist on one
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        client.post(ChannelProvider.TEAMS, TEAMS_HOOK, card());
        server.verify();
    }

    @Test
    void slackGetsBlockKitInsideAColouredAttachmentWithAPlainTextFallback() {
        server.expect(requestTo(SLACK_HOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.text").value("Key expires in 7 days: GitHub / Deploy token"))
                .andExpect(jsonPath("$.attachments[0].color").value("#e69600"))
                .andExpect(jsonPath("$.attachments[0].blocks[0].type").value("header"))
                .andExpect(jsonPath("$.attachments[0].blocks[0].text.type").value("plain_text"))
                .andExpect(jsonPath("$.attachments[0].blocks[0].text.text", Matchers.endsWith("Key expires in 7 days: GitHub / Deploy token")))
                .andExpect(jsonPath("$.attachments[0].blocks[1].text.text").value("The one week reminder."))
                .andExpect(jsonPath("$.attachments[0].blocks[2].fields[0].text").value("*Owner*\nAlex Carter"))
                .andExpect(jsonPath("$.attachments[0].blocks[3].elements[0].url").value("https://keys.example.com/keys/42"))
                // Slack answers a plain-text "ok"
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        client.post(ChannelProvider.SLACK, SLACK_HOOK, card());
        server.verify();
    }

    @Test
    void slackHeadersStayWithinTheirLimit() {
        String longTitle = "x".repeat(300);
        AlertCard card = new AlertCard(Severity.ATTENTION, longTitle, null, null, null, null);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> headerBlock = (java.util.Map<String, Object>) ((List<?>) ((java.util.Map<?, ?>)
                ((List<?>) card.toSlack().get("attachments")).get(0)).get("blocks")).get(0);
        @SuppressWarnings("unchecked")
        String header = (String) ((java.util.Map<String, Object>) headerBlock.get("text")).get("text");
        assertThat(header).startsWith("🔴 ").endsWith("…");
        assertThat(header.length()).isLessThanOrEqualTo(150);
        // and the full title still travels in the fallback text
        assertThat(card.toSlack().get("text")).isEqualTo(longTitle);
    }

    @Test
    void aRemovedWebhookIsSaidInPlainWordsForEitherProduct() {
        server.expect(requestTo(SLACK_HOOK)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN).body("no_service"));

        assertThatThrownBy(() -> client.post(ChannelProvider.SLACK, SLACK_HOOK, card()))
                .isInstanceOf(AlertException.class)
                .hasMessageContaining("Slack rejected the message (HTTP 404)")
                .hasMessageContaining("no_service")
                .hasMessageContaining("removed in Slack");
    }

    @Test
    void onlyHttpsUrlsPointingOutsideTheBoxAreAccepted() {
        assertThat(WebhookClient.validate("  " + SLACK_HOOK + " ")).isEqualTo(SLACK_HOOK);

        assertThatThrownBy(() -> WebhookClient.validate("http://hooks.slack.com/services/x"))
                .isInstanceOf(AlertException.class).hasMessageContaining("https://");
        assertThatThrownBy(() -> WebhookClient.validate("https://localhost:8080/admin"))
                .isInstanceOf(AlertException.class).hasMessageContaining("local or private");
        assertThatThrownBy(() -> WebhookClient.validate("https://127.0.0.1/x"))
                .isInstanceOf(AlertException.class).hasMessageContaining("local or private");
        assertThatThrownBy(() -> WebhookClient.validate("https://10.0.0.5/x"))
                .isInstanceOf(AlertException.class).hasMessageContaining("local or private");
        assertThatThrownBy(() -> WebhookClient.validate("https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(AlertException.class).hasMessageContaining("local or private");
        assertThatThrownBy(() -> WebhookClient.validate("https://db.internal/x"))
                .isInstanceOf(AlertException.class).hasMessageContaining("local or private");
        assertThatThrownBy(() -> WebhookClient.validate(""))
                .isInstanceOf(AlertException.class).hasMessageContaining("Paste the webhook URL");
    }

    @Test
    void providersAreFoundByTheirPathSegment() {
        assertThat(ChannelProvider.fromPath("teams")).isEqualTo(ChannelProvider.TEAMS);
        assertThat(ChannelProvider.fromPath("Slack")).isEqualTo(ChannelProvider.SLACK);
        assertThatThrownBy(() -> ChannelProvider.fromPath("discord")).isInstanceOf(IllegalArgumentException.class);
    }
}
