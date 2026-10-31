package com.sidbhat.apikeyportal.web;

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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sidbhat.apikeyportal.security.PrincipalRefresher;
import com.sidbhat.apikeyportal.user.EmailAlreadyUsedException;
import com.sidbhat.apikeyportal.user.IncorrectPasswordException;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * Your own account: the details you can change about yourself, and your password.
 */
@Controller
@RequestMapping("/profile")
public class ProfileController {

    private final UserAccountService userService;
    private final PrincipalRefresher principalRefresher;

    public ProfileController(UserAccountService userService, PrincipalRefresher principalRefresher) {
        this.userService = userService;
        this.principalRefresher = principalRefresher;
    }

    @GetMapping
    public String profile(@AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", ProfileForm.from(me));
        }
        model.addAttribute("account", me);
        return "profile/view";
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
