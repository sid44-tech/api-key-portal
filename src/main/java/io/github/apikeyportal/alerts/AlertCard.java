package io.github.apikeyportal.alerts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One alert, product-neutral: a coloured title, a sentence, a few facts and a
 * button into the portal. The two formatters turn it into what each webhook
 * expects - an Adaptive Card for Teams, Block Kit for Slack. Built as plain
 * maps because Jackson serialises them and nothing else needs the structure.
 *
 * @param severity colours the card: red for things already wrong, amber for
 *                 things about to be, green for a test
 */
public record AlertCard(Severity severity, String title, String text, List<Fact> facts, String linkTitle,
        String linkUrl) {

    public enum Severity {
        ATTENTION("Attention", "#c62d2d", "🔴"),
        WARNING("Warning", "#e69600", "🟠"),
        GOOD("Good", "#1f8a4c", "🟢"),
        NEUTRAL("Default", "#86868b", "ℹ️");

        final String adaptiveColor;
        final String hex;
        final String emoji;

        Severity(String adaptiveColor, String hex, String emoji) {
            this.adaptiveColor = adaptiveColor;
            this.hex = hex;
            this.emoji = emoji;
        }
    }

    public record Fact(String title, String value) {
    }

    /**
     * The Teams envelope: an Adaptive Card in a message attachment, which
     * Workflows webhooks and the older Office 365 connectors both accept.
     */
    public Map<String, Object> toTeams() {
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(teamsText(title, true));
        if (text != null && !text.isBlank()) {
            body.add(teamsText(text, false));
        }
        if (facts != null && !facts.isEmpty()) {
            List<Map<String, String>> items = new ArrayList<>();
            for (Fact fact : facts) {
                items.add(Map.of("title", fact.title(), "value", fact.value() == null ? "" : fact.value()));
            }
            body.add(Map.of("type", "FactSet", "facts", items));
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("msteams", Map.of("width", "Full"));
        card.put("body", body);
        if (linkUrl != null) {
            card.put("actions", List.of(Map.of("type", "Action.OpenUrl", "title", linkTitle, "url", linkUrl)));
        }
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("content", card);
        return Map.of("type", "message", "attachments", List.of(attachment));
    }

    private Map<String, Object> teamsText(String content, boolean heading) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "TextBlock");
        block.put("text", content);
        block.put("wrap", true);
        if (heading) {
            block.put("size", "Medium");
            block.put("weight", "Bolder");
            block.put("color", severity.adaptiveColor);
        }
        return block;
    }

    /**
     * The Slack envelope: Block Kit inside an attachment, for the coloured bar
     * down the side that is the one thing blocks alone cannot do, with a plain
     * {@code text} fallback for notifications and clients that show nothing else.
     */
    public Map<String, Object> toSlack() {
        List<Map<String, Object>> blocks = new ArrayList<>();
        // Slack caps a header at 150 characters, emoji included, and wants plain text
        blocks.add(Map.of("type", "header", "text", plain(truncate(severity.emoji + " " + title, 150))));
        if (text != null && !text.isBlank()) {
            blocks.add(Map.of("type", "section", "text", mrkdwn(text)));
        }
        if (facts != null && !facts.isEmpty()) {
            List<Map<String, Object>> fields = new ArrayList<>();
            // a section holds at most ten fields
            for (Fact fact : facts.subList(0, Math.min(facts.size(), 10))) {
                fields.add(mrkdwn("*" + fact.title() + "*\n" + (fact.value() == null ? "" : fact.value())));
            }
            blocks.add(Map.of("type", "section", "fields", fields));
        }
        if (linkUrl != null) {
            Map<String, Object> button = new LinkedHashMap<>();
            button.put("type", "button");
            button.put("text", plain(linkTitle));
            button.put("url", linkUrl);
            blocks.add(Map.of("type", "actions", "elements", List.of(button)));
        }
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("color", severity.hex);
        attachment.put("blocks", blocks);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("text", title);
        message.put("attachments", List.of(attachment));
        return message;
    }

    private static Map<String, Object> plain(String content) {
        return Map.of("type", "plain_text", "text", content, "emoji", true);
    }

    private static Map<String, Object> mrkdwn(String content) {
        return Map.of("type", "mrkdwn", "text", content);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
