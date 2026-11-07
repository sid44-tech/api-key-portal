package io.github.apikeyportal.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import io.github.apikeyportal.appaccess.AppAccessService;

/**
 * Tells the navigation whether to offer App access: always to super admins,
 * who need it to switch the feature on, and to everyone else only while it is on.
 */
@ControllerAdvice
public class AppAccessAdvice {

    /** Optional so that sliced web tests, which have no service layer, can still render pages. */
    private final ObjectProvider<AppAccessService> appAccess;

    public AppAccessAdvice(ObjectProvider<AppAccessService> appAccess) {
        this.appAccess = appAccess;
    }

    @ModelAttribute("appAccessEnabled")
    public Boolean appAccessEnabled() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Boolean.FALSE;
        }
        AppAccessService service = appAccess.getIfAvailable();
        return service != null && service.isEnabled();
    }
}
