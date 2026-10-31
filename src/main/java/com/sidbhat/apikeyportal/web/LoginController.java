package com.sidbhat.apikeyportal.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.sidbhat.apikeyportal.sso.SsoError;
import com.sidbhat.apikeyportal.sso.SsoService;

@Controller
public class LoginController {

    private final SsoService ssoService;

    public LoginController(SsoService ssoService) {
        this.ssoService = ssoService;
    }

    @GetMapping("/login")
    public String login(@RequestParam(value = "ssoError", required = false) String ssoError, Model model) {
        // the button only appears once a super admin has connected a provider
        ssoService.signInLabel().ifPresent(label -> model.addAttribute("ssoLabel", label));
        model.addAttribute("ssoStartUrl", "/oauth2/authorization/" + SsoService.REGISTRATION_ID);
        if (ssoError != null) {
            model.addAttribute("ssoErrorMessage", SsoError.fromCode(ssoError).getMessage());
        }
        return "login";
    }
}
