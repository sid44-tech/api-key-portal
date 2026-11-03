package com.sidbhat.apikeyportal.theme;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The little colour arithmetic a palette needs: hex in, hex or rgba out.
 * Deliberately not a colour library - six-digit sRGB hex is all the form
 * accepts, and mixing in that space is good enough for tints and hover shades.
 */
final class Colors {

    /** Six hex digits, with or without the leading hash; three-digit shorthand is not accepted. */
    static final Pattern HEX = Pattern.compile("^#?[0-9a-fA-F]{6}$");

    private Colors() {
    }

    static boolean isHex(String value) {
        return value != null && HEX.matcher(value.trim()).matches();
    }

    /** {@code "0A5FD6"} or {@code " #0a5fd6 "} to {@code "#0a5fd6"}. */
    static String normalize(String value) {
        if (!isHex(value)) {
            throw new IllegalArgumentException("not a six-digit hex colour: " + value);
        }
        String digits = value.trim();
        if (digits.startsWith("#")) {
            digits = digits.substring(1);
        }
        return "#" + digits.toLowerCase(Locale.ROOT);
    }

    static int[] rgb(String hex) {
        String digits = normalize(hex).substring(1);
        return new int[] {
                Integer.parseInt(digits.substring(0, 2), 16),
                Integer.parseInt(digits.substring(2, 4), 16),
                Integer.parseInt(digits.substring(4, 6), 16)};
    }

    static String hex(int[] rgb) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", clamp(rgb[0]), clamp(rgb[1]), clamp(rgb[2]));
    }

    /** {@code "10, 95, 214"} - the form Bootstrap's {@code --bs-*-rgb} variables take. */
    static String rgbList(String hex) {
        int[] c = rgb(hex);
        return c[0] + ", " + c[1] + ", " + c[2];
    }

    /** {@code "rgba(10, 95, 214, 0.10)"} - a tint of the colour, for soft backgrounds. */
    static String rgba(String hex, double alpha) {
        return "rgba(" + rgbList(hex) + ", " + String.format(Locale.ROOT, "%.2f", alpha) + ")";
    }

    /** {@code weight} of {@code a} blended with the rest of {@code b}, both hex. */
    static String mix(String a, String b, double weight) {
        int[] ca = rgb(a);
        int[] cb = rgb(b);
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = (int) Math.round(ca[i] * weight + cb[i] * (1 - weight));
        }
        return hex(out);
    }

    /** The colour moved {@code amount} (0-1) towards black. */
    static String darken(String hex, double amount) {
        return mix(hex, "#000000", 1 - amount);
    }

    /** The colour moved {@code amount} (0-1) towards white. */
    static String lighten(String hex, double amount) {
        return mix(hex, "#ffffff", 1 - amount);
    }

    private static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }
}
