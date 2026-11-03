package io.github.apikeyportal.theme;

/**
 * The two colour schemes the portal renders, following the operating system.
 * Each has its own palette because a colour that reads well on white rarely
 * does on black.
 */
public enum ThemeMode {

    LIGHT("Light", ":root"),
    DARK("Dark", ":root[data-bs-theme=\"dark\"]");

    private final String label;

    /** The selector app.css uses for this mode's tokens, so overrides land at the same specificity. */
    private final String selector;

    ThemeMode(String label, String selector) {
        this.label = label;
        this.selector = selector;
    }

    public String getLabel() {
        return label;
    }

    public String getSelector() {
        return selector;
    }

    /** The form field prefix: {@code light.accent}, {@code dark.canvas}. */
    public String getPrefix() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
