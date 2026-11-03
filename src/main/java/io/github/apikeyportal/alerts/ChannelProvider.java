package io.github.apikeyportal.alerts;

import java.util.Locale;
import java.util.Map;

/**
 * The chat products the portal can post alerts into. Each takes a webhook URL
 * and JSON; they differ only in the shape of the JSON, which is what the
 * formatter decides. Adding one is a constant here, a formatter on
 * {@link AlertCard}, and a setup card in the template.
 */
public enum ChannelProvider {

    TEAMS("Microsoft Teams", "bi-microsoft-teams", "teams") {
        @Override
        public Map<String, Object> payload(AlertCard card) {
            return card.toTeams();
        }
    },

    SLACK("Slack", "bi-slack", "slack") {
        @Override
        public Map<String, Object> payload(AlertCard card) {
            return card.toSlack();
        }
    };

    private final String label;

    /** Bootstrap icon class. */
    private final String icon;

    /** The segment in {@code /integrations/alerts/{path}}. */
    private final String path;

    ChannelProvider(String label, String icon, String path) {
        this.label = label;
        this.icon = icon;
        this.path = path;
    }

    /** The card as this product's webhook wants it. */
    public abstract Map<String, Object> payload(AlertCard card);

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }

    public String getPath() {
        return path;
    }

    /** @throws IllegalArgumentException for a path that is not a provider, which the web layer turns into a 404 */
    public static ChannelProvider fromPath(String path) {
        for (ChannelProvider provider : values()) {
            if (provider.path.equals(path == null ? null : path.toLowerCase(Locale.ROOT))) {
                return provider;
            }
        }
        throw new IllegalArgumentException("No alert provider at '" + path + "'");
    }
}
