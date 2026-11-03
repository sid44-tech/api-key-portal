package io.github.apikeyportal.theme;

/**
 * The seven colours an administrator chooses for one mode, and how the rest of
 * the design tokens follow from them.
 * <p>
 * Seven rather than the thirty-odd variables in {@code app.css} on purpose: the
 * hover shade of the accent, the soft tint behind a status pill, the two greys
 * for secondary text - these are relationships, not choices, and a page where
 * each had been picked by hand would be a page where some had been picked
 * wrong. Choose the base colours and the derived ones stay in step.
 *
 * @param accent  links, primary buttons, focus rings
 * @param canvas  the page background
 * @param surface cards and other raised panels
 * @param ink     body text; the two secondary greys are mixed from it and the canvas
 * @param success the green of "active" and "created"
 * @param warning the amber of "expiring soon"
 * @param danger  the red of "expired" and "delete"
 */
public record ThemePalette(String accent, String canvas, String surface, String ink, String success,
        String warning, String danger) {

    /** What app.css ships with, so the form starts from the real values and "reset" has a meaning. */
    public static final ThemePalette DEFAULT_LIGHT = new ThemePalette("#0a5fd6", "#f5f5f7", "#ffffff", "#1d1d1f",
            "#1f8a4c", "#a86400", "#c62d2d");

    public static final ThemePalette DEFAULT_DARK = new ThemePalette("#3b8cf6", "#000000", "#1c1c1e", "#f5f5f7",
            "#4cc27a", "#f0a830", "#f06a6a");

    public ThemePalette {
        accent = Colors.normalize(accent);
        canvas = Colors.normalize(canvas);
        surface = Colors.normalize(surface);
        ink = Colors.normalize(ink);
        success = Colors.normalize(success);
        warning = Colors.normalize(warning);
        danger = Colors.normalize(danger);
    }

    public static ThemePalette defaultFor(ThemeMode mode) {
        return mode == ThemeMode.DARK ? DEFAULT_DARK : DEFAULT_LIGHT;
    }

    /**
     * This palette as a block of CSS custom properties for {@code mode}, at the
     * same selector app.css declares them, so they override cleanly. Tints are
     * stronger in dark mode, as app.css's own are: a 10% wash of colour that
     * reads on white disappears on black.
     */
    public String toCss(ThemeMode mode) {
        boolean dark = mode == ThemeMode.DARK;
        // the hover shade goes the way the mode's contrast goes: darker on light, lighter on dark
        String accentHover = dark ? Colors.lighten(accent, 0.15) : Colors.darken(accent, 0.15);
        StringBuilder css = new StringBuilder();
        css.append(mode.getSelector()).append(" {\n");
        css.append("    --kp-canvas: ").append(canvas).append(";\n");
        css.append("    --kp-surface: ").append(surface).append(";\n");
        css.append("    --kp-surface-muted: ").append(Colors.mix(surface, canvas, 0.85)).append(";\n");
        css.append("    --kp-ink: ").append(ink).append(";\n");
        css.append("    --kp-ink-2: ").append(Colors.mix(ink, canvas, 0.75)).append(";\n");
        css.append("    --kp-ink-3: ").append(Colors.mix(ink, canvas, 0.55)).append(";\n");
        css.append("    --kp-accent: ").append(accent).append(";\n");
        css.append("    --kp-accent-hover: ").append(accentHover).append(";\n");
        css.append("    --kp-accent-soft: ").append(Colors.rgba(accent, dark ? 0.18 : 0.10)).append(";\n");
        css.append("    --kp-accent-ring: ").append(Colors.rgba(accent, dark ? 0.35 : 0.22)).append(";\n");
        css.append("    --kp-green: ").append(success).append(";\n");
        css.append("    --kp-green-soft: ").append(Colors.rgba(success, dark ? 0.16 : 0.12)).append(";\n");
        css.append("    --kp-amber: ").append(warning).append(";\n");
        css.append("    --kp-amber-soft: ").append(Colors.rgba(warning, dark ? 0.18 : 0.16)).append(";\n");
        css.append("    --kp-red: ").append(danger).append(";\n");
        css.append("    --kp-red-soft: ").append(Colors.rgba(danger, dark ? 0.16 : 0.11)).append(";\n");
        css.append("    --bs-link-color-rgb: ").append(Colors.rgbList(accent)).append(";\n");
        css.append("    --bs-link-hover-color-rgb: ").append(Colors.rgbList(accentHover)).append(";\n");
        css.append("    --bs-primary-rgb: ").append(Colors.rgbList(accent)).append(";\n");
        css.append("}\n");
        return css.toString();
    }
}
