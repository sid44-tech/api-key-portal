package com.sidbhat.apikeyportal.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sidbhat.apikeyportal.theme.Logo;
import com.sidbhat.apikeyportal.theme.ThemeService;

/**
 * Serves the uploaded logo. Public, like the colour stylesheet: the sign-in
 * page shows it, and it is a picture.
 */
@RestController
public class ThemeLogoController {

    /**
     * Sent with every logo so that an SVG opened directly - rather than inside
     * an {@code <img>}, where script never runs anyway - cannot run anything
     * either: no scripts, no fetches, no frames, sandboxed. Spring Security's
     * own policy header defers to one the response already carries.
     */
    static final String LOGO_CSP = "default-src 'none'; style-src 'unsafe-inline'; sandbox";

    private final ThemeService themeService;

    public ThemeLogoController(ThemeService themeService) {
        this.themeService = themeService;
    }

    @GetMapping("/theme/logo")
    public ResponseEntity<byte[]> logo() {
        return themeService.logo()
                .map(this::respond)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> respond(Logo logo) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .cacheControl(CacheControl.noCache())
                .eTag("\"" + themeService.version() + "\"")
                .header("Content-Security-Policy", LOGO_CSP)
                .header("Content-Disposition", "inline")
                .body(logo.bytes());
    }
}
