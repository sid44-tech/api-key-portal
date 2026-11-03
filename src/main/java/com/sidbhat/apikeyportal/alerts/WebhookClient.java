package com.sidbhat.apikeyportal.alerts;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Posts cards to an incoming webhook. One call, no SDK: a webhook is a URL that
 * takes JSON, and the provider decides the JSON.
 * <p>
 * The URL is checked before it is used. It is pasted by a group admin and the
 * portal will POST to it from inside the network, so it has to be HTTPS and
 * has to point somewhere outside the box - not at localhost, a private
 * address or a metadata endpoint. The alerts carry no secrets, but a portal
 * that can be told to POST to arbitrary internal addresses is still a portal
 * that can be told to.
 */
@Component
public class WebhookClient {

    private final RestClient restClient;

    public WebhookClient() {
        this(defaultBuilder());
    }

    WebhookClient(RestClient.Builder builder) {
        this.restClient = builder.clone().build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * @throws AlertException if the URL is not one the portal will post to
     */
    public static String validate(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new AlertException("Paste the webhook URL.");
        }
        URI uri;
        try {
            uri = new URI(webhookUrl.trim());
        } catch (URISyntaxException e) {
            throw new AlertException("That is not a valid URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new AlertException("The webhook URL must start with https://.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new AlertException("The webhook URL has no host.");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal") || isPrivateLiteral(lower)) {
            throw new AlertException("The webhook URL must point at the chat service, not at a local or private address.");
        }
        return uri.toString();
    }

    /** Loopback, link-local, site-local and unspecified addresses written as literals. */
    private static boolean isPrivateLiteral(String host) {
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!literal.matches("[0-9a-fA-F:.]+") || !literal.contains(".") && !literal.contains(":")) {
            return false;
        }
        try {
            // literals only: no DNS lookup happens for a dotted quad or an IPv6 form
            InetAddress address = InetAddress.getByName(literal);
            return address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress() || literal.startsWith("169.254.") || literal.startsWith("fd")
                    || literal.startsWith("fc");
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /**
     * @throws AlertException if the service refused the message or could not be reached
     */
    public void post(ChannelProvider provider, String webhookUrl, AlertCard card) {
        String url = validate(webhookUrl);
        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(provider.payload(card))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String body = e.getResponseBodyAsString();
            String detail = body == null || body.isBlank() || body.length() > 120 ? "" : ": " + body.trim();
            throw new AlertException(provider.getLabel() + " rejected the message (HTTP " + status + ")" + detail
                    + (status == 401 || status == 403 || status == 404
                            ? ". The webhook may have been removed in " + provider.getLabel() + "." : ""), e);
        } catch (RestClientException e) {
            throw new AlertException("Could not reach the " + provider.getLabel() + " webhook: " + e.getMessage(), e);
        }
    }
}
