package io.github.apikeyportal.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import io.github.apikeyportal.theme.ThemeService;

/**
 * Gives every page the version of the colour stylesheet, so the layout can
 * link {@code /theme.css?v=...} and a save is seen on the very next page
 * rather than whenever the browser's cache feels like it.
 * <p>
 * The service is optional so that controller slice tests, which load every
 * {@code @ControllerAdvice} but only the beans they ask for, keep working.
 */
@ControllerAdvice
public class ThemeAdvice {

    private final ObjectProvider<ThemeService> themeService;

    public ThemeAdvice(ObjectProvider<ThemeService> themeService) {
        this.themeService = themeService;
    }

    @ModelAttribute("themeVersion")
    public String themeVersion() {
        ThemeService service = themeService.getIfAvailable();
        return service == null ? "0" : service.version();
    }

    /** The name in the header, the titles and the sign-in page. */
    @ModelAttribute("portalName")
    public String portalName() {
        ThemeService service = themeService.getIfAvailable();
        return service == null ? ThemeService.DEFAULT_NAME : service.portalName();
    }

    /** Whether to show an uploaded logo in place of the key mark. */
    @ModelAttribute("hasLogo")
    public boolean hasLogo() {
        ThemeService service = themeService.getIfAvailable();
        return service != null && service.hasLogo();
    }
}
