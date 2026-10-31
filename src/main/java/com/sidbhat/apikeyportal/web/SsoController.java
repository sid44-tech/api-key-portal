package com.sidbhat.apikeyportal.web;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.sso.SsoConfiguration;
import com.sidbhat.apikeyportal.sso.SsoException;
import com.sidbhat.apikeyportal.sso.SsoService;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.UserGroup;

/**
 * Connecting the organisation's identity provider. Super admin only - the route
 * is restricted in {@code SecurityConfig}, since this page decides who can get
 * into the portal at all.
 */
@Controller
@RequestMapping("/sso")
public class SsoController {

    private final SsoService ssoService;
    private final UserAccountService userService;
    private final ActivityService activityService;

    public SsoController(SsoService ssoService, UserAccountService userService, ActivityService activityService) {
        this.ssoService = ssoService;
        this.userService = userService;
        this.activityService = activityService;
    }

    @GetMapping
    public String settings(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", ssoService.find().map(SsoForm::from).orElseGet(SsoForm::new));
        }
        prepareModel(model);
        return "sso/settings";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal PortalUser principal, @Valid @ModelAttribute("form") SsoForm form,
            BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        UserAccount me = userService.getCurrent(principal);
        if (!bindingResult.hasErrors()) {
            try {
                UserGroup defaultGroup = form.getDefaultGroupId() == null ? null
                        : userService.getGroup(form.getDefaultGroupId());
                SsoConfiguration saved = ssoService.save(form.toDetails(), defaultGroup, me);
                activityService.record(ActivityAction.SSO_CONFIGURED, me, saved.getId(), saved.getDisplayName(),
                        saved.isEnabled() ? "Enabled" : "Saved but switched off");
                redirectAttributes.addFlashAttribute("success", saved.isEnabled()
                        ? "Single sign-on with " + saved.getDisplayName()
                                + " is on. The connection to the provider was verified."
                        : "Saved. Single sign-on is switched off until you enable it.");
                return "redirect:/sso";
            } catch (SsoException e) {
                // a provider that can't be reached is a message on the form, not a 500
                bindingResult.reject("sso.connect.failed", e.getMessage());
            }
        }
        prepareModel(model);
        return "sso/settings";
    }

    @PostMapping("/disable")
    public String disable(@AuthenticationPrincipal PortalUser principal, RedirectAttributes redirectAttributes) {
        UserAccount me = userService.getCurrent(principal);
        ssoService.find().ifPresent(config -> activityService.record(ActivityAction.SSO_CONFIGURED, me,
                config.getId(), config.getDisplayName(), "Disabled"));
        ssoService.disable(me);
        redirectAttributes.addFlashAttribute("success",
                "Single sign-on is off. Existing accounts keep working; nobody new can be created through it.");
        return "redirect:/sso";
    }

    private void prepareModel(Model model) {
        model.addAttribute("groups", userService.findAllGroups());
        // the value the admin has to paste into the provider's app registration
        model.addAttribute("redirectUri", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/login/oauth2/code/" + SsoService.REGISTRATION_ID)
                .build()
                .toUriString());
        model.addAttribute("configured", ssoService.find().isPresent());
    }
}
