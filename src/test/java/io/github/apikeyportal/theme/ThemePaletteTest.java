package io.github.apikeyportal.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ThemePaletteTest {

    @Test
    void hexIsNormalisedToLowerCaseWithAHash() {
        assertThat(Colors.normalize("0A5FD6")).isEqualTo("#0a5fd6");
        assertThat(Colors.normalize("  #0A5FD6 ")).isEqualTo("#0a5fd6");
        assertThatThrownBy(() -> Colors.normalize("#abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Colors.normalize("blue")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Colors.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultsReproduceWhatAppCssShipsWith() {
        String light = ThemePalette.DEFAULT_LIGHT.toCss(ThemeMode.LIGHT);

        assertThat(light).startsWith(":root {");
        assertThat(light).contains("--kp-accent: #0a5fd6;");
        // app.css has #0950b5; the derivation lands within a shade of it
        assertThat(light).contains("--kp-accent-hover: #0951b6;");
        assertThat(light).contains("--kp-accent-soft: rgba(10, 95, 214, 0.10);");
        assertThat(light).contains("--kp-accent-ring: rgba(10, 95, 214, 0.22);");
        assertThat(light).contains("--bs-primary-rgb: 10, 95, 214;");
        assertThat(light).contains("--kp-green: #1f8a4c;");
        assertThat(light).contains("--kp-red-soft: rgba(198, 45, 45, 0.11);");
    }

    @Test
    void darkModeLightensTheHoverShadeAndStrengthensTheTints() {
        String dark = ThemePalette.DEFAULT_DARK.toCss(ThemeMode.DARK);

        assertThat(dark).startsWith(":root[data-bs-theme=\"dark\"] {");
        // app.css has #5a9ff8
        assertThat(dark).contains("--kp-accent-hover: #589df7;");
        assertThat(dark).contains("--kp-accent-soft: rgba(59, 140, 246, 0.18);");
        assertThat(dark).contains("--kp-green-soft: rgba(76, 194, 122, 0.16);");
    }

    @Test
    void theSecondaryGreysAreMixedFromTextAndBackground() {
        // black text on white: the softer greys sit between the two
        ThemePalette palette = new ThemePalette("#0a5fd6", "#ffffff", "#ffffff", "#000000", "#1f8a4c", "#a86400",
                "#c62d2d");
        String css = palette.toCss(ThemeMode.LIGHT);

        assertThat(css).contains("--kp-ink-2: #404040;");
        assertThat(css).contains("--kp-ink-3: #737373;");
        // and the muted surface leans towards the canvas
        assertThat(new ThemePalette("#0a5fd6", "#000000", "#ffffff", "#000000", "#1f8a4c", "#a86400", "#c62d2d")
                .toCss(ThemeMode.LIGHT)).contains("--kp-surface-muted: #d9d9d9;");
    }

    @Test
    void aPaletteNormalisesWhateverCaseItWasTypedIn() {
        ThemePalette palette = new ThemePalette("FF0000", "#FFFFFF", "#ffffff", "#000000", "#00FF00", "#FFA500",
                "#ff0000");

        assertThat(palette.accent()).isEqualTo("#ff0000");
        assertThat(palette.success()).isEqualTo("#00ff00");
    }
}
