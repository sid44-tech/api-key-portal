package io.github.apikeyportal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.github.apikeyportal.theme.ThemeMode;
import io.github.apikeyportal.theme.ThemePalette;

/**
 * Backing object for the appearance page: seven colours for each of the two
 * modes, as hex text. The colour picker beside each field is a mirror of the
 * text, not a field of its own, so what is submitted is always the hex.
 */
public class AppearanceForm {

    /** The portal's name, in the header and the page titles. */
    @NotBlank(message = "give the portal a name")
    @Size(max = 60)
    private String portalName;

    @Valid
    private ColorSet light = new ColorSet();

    @Valid
    private ColorSet dark = new ColorSet();

    public static AppearanceForm from(String portalName, ThemePalette light, ThemePalette dark) {
        AppearanceForm form = new AppearanceForm();
        form.portalName = portalName;
        form.light = ColorSet.from(light);
        form.dark = ColorSet.from(dark);
        return form;
    }

    public ThemePalette palette(ThemeMode mode) {
        return (mode == ThemeMode.DARK ? dark : light).toPalette();
    }

    public String getPortalName() {
        return portalName;
    }

    public void setPortalName(String portalName) {
        this.portalName = portalName;
    }

    public ColorSet getLight() {
        return light;
    }

    public void setLight(ColorSet light) {
        this.light = light;
    }

    public ColorSet getDark() {
        return dark;
    }

    public void setDark(ColorSet dark) {
        this.dark = dark;
    }

    /** One mode's seven colours. Every field must be a six-digit hex colour. */
    public static class ColorSet {

        private static final String HEX = "^#?[0-9a-fA-F]{6}$";
        private static final String MESSAGE = "must be a six-digit hex colour, like #0a5fd6";

        @Pattern(regexp = HEX, message = MESSAGE)
        private String accent;
        @Pattern(regexp = HEX, message = MESSAGE)
        private String canvas;
        @Pattern(regexp = HEX, message = MESSAGE)
        private String surface;
        @Pattern(regexp = HEX, message = MESSAGE)
        private String ink;
        @Pattern(regexp = HEX, message = MESSAGE)
        private String success;
        @Pattern(regexp = HEX, message = MESSAGE)
        private String warning;
        @Pattern(regexp = HEX, message = MESSAGE)
        private String danger;

        static ColorSet from(ThemePalette palette) {
            ColorSet set = new ColorSet();
            set.accent = palette.accent();
            set.canvas = palette.canvas();
            set.surface = palette.surface();
            set.ink = palette.ink();
            set.success = palette.success();
            set.warning = palette.warning();
            set.danger = palette.danger();
            return set;
        }

        ThemePalette toPalette() {
            // the record normalises case and the leading hash
            return new ThemePalette(accent, canvas, surface, ink, success, warning, danger);
        }

        public String getAccent() {
            return accent;
        }

        public void setAccent(String accent) {
            this.accent = accent;
        }

        public String getCanvas() {
            return canvas;
        }

        public void setCanvas(String canvas) {
            this.canvas = canvas;
        }

        public String getSurface() {
            return surface;
        }

        public void setSurface(String surface) {
            this.surface = surface;
        }

        public String getInk() {
            return ink;
        }

        public void setInk(String ink) {
            this.ink = ink;
        }

        public String getSuccess() {
            return success;
        }

        public void setSuccess(String success) {
            this.success = success;
        }

        public String getWarning() {
            return warning;
        }

        public void setWarning(String warning) {
            this.warning = warning;
        }

        public String getDanger() {
            return danger;
        }

        public void setDanger(String danger) {
            this.danger = danger;
        }
    }
}
