package io.github.apikeyportal.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class LogoTest {

    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    static final byte[] SVG = "<?xml version=\"1.0\"?>\n<!-- logo -->\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void theTypeComesFromTheBytesNotTheFileName() {
        assertThat(Logo.validated(PNG).contentType()).isEqualTo("image/png");
        assertThat(Logo.validated(JPEG).contentType()).isEqualTo("image/jpeg");
        assertThat(Logo.validated("GIF89a....".getBytes(StandardCharsets.UTF_8)).contentType()).isEqualTo("image/gif");
        assertThat(Logo.validated("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.UTF_8)).contentType())
                .isEqualTo("image/webp");
        assertThat(Logo.validated(SVG).contentType()).isEqualTo("image/svg+xml");
    }

    @Test
    void anythingThatIsNotAnImageIsRefusedInPlainWords() {
        assertThatThrownBy(() -> Logo.validated("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an image");
        assertThatThrownBy(() -> Logo.validated("%PDF-1.4".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Logo.validated(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Choose an image");
    }

    @Test
    void aLogoBiggerThanTheCapIsRefused() {
        byte[] huge = new byte[Logo.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);

        assertThatThrownBy(() -> Logo.validated(huge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("512 KB or smaller");
    }
}
