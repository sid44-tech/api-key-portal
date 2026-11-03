package io.github.apikeyportal.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.github.apikeyportal.integration.ServiceProvider;
import io.github.apikeyportal.rotation.KeyRotationService;
import io.github.apikeyportal.security.PrincipalRefresher;
import io.github.apikeyportal.user.EmailAlreadyUsedException;
import io.github.apikeyportal.user.IncorrectPasswordException;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountService;

/**
 * Your own account: the details you can change about yourself, and your password.
 */
@Controller
@RequestMapping("/profile")
public class ProfileController {

    private final UserAccountService userService;
    private final PrincipalRefresher principalRefresher;
    private final KeyRotationService rotationService;

    public ProfileController(UserAccountService userService, PrincipalRefresher principalRefresher,
            KeyRotationService rotationService) {
        this.userService = userService;
        this.principalRefresher = principalRefresher;
        this.rotationService = rotationService;
    }

    @GetMapping
    public String profile(@AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", ProfileForm.from(me));
        }
        model.addAttribute("account", me);
        addRotationSettings(model, me);
        return "profile/view";
    }

    /**
     * The owner's per-provider switch: may the portal mint replacements for their
     * expiring keys there. Offered only for providers that support it.
     */
    @PostMapping("/rotation")
    public String setRotationPreference(@AuthenticationPrincipal PortalUser principal,
            @RequestParam("provider") ServiceProvider provider,
            @RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
            RedirectAttributes redirectAttributes) {
        if (!provider.supportsRotation()) {
            throw new IllegalArgumentException(provider.getLabel() + " does not support automatic replacement");
        }
        rotationService.setPreference(userService.getCurrent(principal), provider, enabled);
        redirectAttributes.addFlashAttribute("success", enabled
                ? "Expiring " + provider.getLabel() + " keys you hold will be replaced automatically, "
                        + "a week before they expire."
                : "Your " + provider.getLabel() + " keys won't be replaced automatically.");
        return "redirect:/profile";
    }

    private void addRotationSettings(Model model, UserAccount me) {
        Map<ServiceProvider, Boolean> settings = new LinkedHashMap<>();
        for (ServiceProvider provider : ServiceProvider.values()) {
            if (provider.supportsRotation()) {
                settings.put(provider, rotationService.isPreferenceEnabled(me, provider));
            }
        }
        model.addAttribute("rotationSettings", settings);
    }

    @PostMapping
    public String updateProfile(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") ProfileForm form, BindingResult bindingResult, Model model,
            HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirectAttributes) {
        UserAccount me = userService.getCurrent(principal);
        if (!bindingResult.hasErrors()) {
            try {
                UserAccount updated = userService.updateProfile(me.getId(), form.toDetails());
                // the session holds a snapshot of the account; keep the name and login in step
                principalRefresher.refresh(updated, request, response);
                redirectAttributes.addFlashAttribute("success", "Profile updated.");
                return "redirect:/profile";
            } catch (EmailAlreadyUsedException e) {
                bindingResult.rejectValue("email", "profile.email.taken", e.getMessage());
            }
        }
        model.addAttribute("account", me);
        addRotationSettings(model, me);
        return "profile/view";
    }

    @GetMapping("/password")
    public String passwordForm(@AuthenticationPrincipal PortalUser principal, Model model) {
        model.addAttribute("form", new PasswordChangeForm());
        model.addAttribute("forced", principal.isMustChangePassword());
        return "profile/password";
    }

    @PostMapping("/password")
    public String changePassword(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") PasswordChangeForm form, BindingResult bindingResult,
            HttpServletRequest request, Model model) {
        model.addAttribute("forced", principal.isMustChangePassword());
        if (!bindingResult.hasErrors()) {
            try {
                userService.changePassword(principal.getId(), form.getCurrentPassword(), form.getNewPassword());
                // the session still holds the pre-change principal, so start a fresh one
                SecurityContextHolder.clearContext();
                request.getSession().invalidate();
                return "redirect:/login?passwordChanged";
            } catch (IncorrectPasswordException e) {
                bindingResult.rejectValue("currentPassword", "password.incorrect", e.getMessage());
            }
        }
        return "profile/password";
    }
}
