package com.sidbhat.apikeyportal.theme;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * An uploaded logo, checked before it is stored.
 * <p>
 * The content type is decided from the bytes, never from what the browser
 * said: a file is what it is, not what it is called. Only image formats a
 * browser renders in an {@code <img>} are accepted, and SVG is allowed because
 * it is the format logos actually come in - the endpoint that serves it sends
 * a policy that keeps any script inside it from ever running.
 *
 * @param bytes       the image as uploaded
 * @param contentType what it turned out to be
 */
public record Logo(byte[] bytes, String contentType) {

    /** Plenty for a header mark; a logo bigger than this is a photo. */
    public static final int MAX_BYTES = 512 * 1024;

    public static final String ACCEPTED = "PNG, JPEG, GIF, WebP or SVG";

    /**
     * @throws IllegalArgumentException with a message fit for the form if the
     *         file is empty, too large, or not one of the accepted image formats
     */
    public static Logo validated(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Choose an image file to upload.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("The logo must be " + (MAX_BYTES / 1024) + " KB or smaller.");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new IllegalArgumentException("That file is not an image the portal can use. Upload a "
                    + ACCEPTED + " file.");
        }
        return new Logo(bytes, type);
    }

    private static String sniff(byte[] b) {
        if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(b, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (b.length >= 12 && startsWith(b, 'R', 'I', 'F', 'F')
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        // SVG is text: an <svg element somewhere near the top, after any BOM,
        // XML declaration, comments or doctype
        String head = new String(b, 0, Math.min(b.length, 2048), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        if (head.contains("<svg")) {
            return "image/svg+xml";
        }
        return null;
    }

    private static boolean startsWith(byte[] b, int... magic) {
        if (b.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((b[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Logo other && Arrays.equals(bytes, other.bytes)
                && contentType.equals(other.contentType);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + contentType.hashCode();
    }

    @Override
    public String toString() {
        return "Logo{" + contentType + ", " + bytes.length + " bytes}";
    }
}
