package com.sidbhat.apikeyportal.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.sidbhat.apikeyportal.security.LockoutProperties;
import com.sidbhat.apikeyportal.sso.SsoError;
import com.sidbhat.apikeyportal.sso.SsoService;

@Controller
public class LoginController {

    private final SsoService ssoService;
    private final LockoutProperties lockout;

    public LoginController(SsoService ssoService, LockoutProperties lockout) {
        this.ssoService = ssoService;
        this.lockout = lockout;
    }

    @GetMapping("/login")
    public String login(@RequestParam(value = "ssoError", required = false) String ssoError, Model model) {
        // the button only appears once a super admin has connected a provider
        ssoService.signInLabel().ifPresent(label -> model.addAttribute("ssoLabel", label));
        model.addAttribute("ssoStartUrl", "/oauth2/authorization/" + SsoService.REGISTRATION_ID);
        // how long a locked-out visitor is being asked to wait, rounded up so it
        // is never an invitation to come back before the lock has actually lifted
        model.addAttribute("lockoutMinutes", Math.max(1, (lockout.getLockoutFor().getSeconds() + 59) / 60));
        if (ssoError != null) {
            model.addAttribute("ssoErrorMessage", SsoError.fromCode(ssoError).getMessage());
        }
        return "login";
    }
}
