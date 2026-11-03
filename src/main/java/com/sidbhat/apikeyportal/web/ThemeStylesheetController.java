package com.sidbhat.apikeyportal.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sidbhat.apikeyportal.theme.ThemeService;

/**
 * Serves the chosen colours as a stylesheet. The Content-Security-Policy allows
 * no inline styles, so this same-origin file is how a colour picked on the
 * appearance page reaches the browser. Public, like the rest of the static
 * assets: the sign-in page wears the colours too, and there is nothing in here
 * worth protecting.
 */
@RestController
public class ThemeStylesheetController {

    private final ThemeService themeService;

    public ThemeStylesheetController(ThemeService themeService) {
        this.themeService = themeService;
    }

    /**
     * Revalidated on every page load (cheap: a 304 against the version as ETag)
     * rather than cached for long, so a change shows up at the next page even
     * for a browser that did not get the versioned link.
     */
    @GetMapping(value = "/theme.css", produces = "text/css")
    public ResponseEntity<String> stylesheet() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/css;charset=UTF-8"))
                .cacheControl(CacheControl.noCache())
                .eTag("\"" + themeService.version() + "\"")
                .body(themeService.stylesheet());
    }
}
