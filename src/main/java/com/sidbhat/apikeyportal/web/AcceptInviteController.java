package com.sidbhat.apikeyportal.web;

import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.sidbhat.apikeyportal.invite.InvalidInvitationException;
import com.sidbhat.apikeyportal.invite.InvitationService;
import com.sidbhat.apikeyportal.invite.UserInvitation;
import com.sidbhat.apikeyportal.user.EmailAlreadyUsedException;

/**
 * The one page an anonymous visitor can reach: accepting an invitation.
 * <p>
 * The token in the URL is the whole of the authorisation, and it is checked
 * again on the POST - a page left open past the invitation's day is no good.
 */
@Controller
@RequestMapping("/invite/{token}")
public class AcceptInviteController {

    private final InvitationService invitationService;

    public AcceptInviteController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @GetMapping
    public String form(@PathVariable String token, Model model) {
        model.addAttribute("invitation", invitationService.requirePending(token));
        model.addAttribute("token", token);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new AcceptInviteForm());
        }
        return "invite/accept";
    }

    @PostMapping
    public String accept(@PathVariable String token, @Valid @ModelAttribute("form") AcceptInviteForm form,
            BindingResult bindingResult, Model model) {
        UserInvitation invitation = invitationService.requirePending(token);
        if (!bindingResult.hasErrors()) {
            try {
                invitationService.accept(token, form.getDisplayName(), form.getPassword());
                // they sign in on the login page rather than here, so the session starts clean
                return "redirect:/login?registered";
            } catch (EmailAlreadyUsedException e) {
                bindingResult.reject("invite.email.taken", e.getMessage());
            }
        }
        model.addAttribute("invitation", invitation);
        model.addAttribute("token", token);
        return "invite/accept";
    }

    /** A bad, used or expired link gets the same page either way, never a stack trace. */
    @ExceptionHandler(InvalidInvitationException.class)
    public String invalidInvitation(InvalidInvitationException e, Model model) {
        model.addAttribute("reason", e.getMessage());
        return "invite/invalid";
    }
}
