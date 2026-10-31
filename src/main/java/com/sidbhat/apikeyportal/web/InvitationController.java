package com.sidbhat.apikeyportal.web;

import java.time.Instant;

import jakarta.validation.Valid;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.sidbhat.apikeyportal.invite.InvitationException;
import com.sidbhat.apikeyportal.invite.InvitationService;
import com.sidbhat.apikeyportal.invite.IssuedInvitation;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * Inviting people in. A super admin can invite into any group, a group admin
 * only into their own; the service enforces both, this only decides what the
 * form offers.
 */
@Controller
@RequestMapping("/invitations")
public class InvitationController {

    private final InvitationService invitationService;
    private final UserAccountService userService;

    public InvitationController(InvitationService invitationService, UserAccountService userService) {
        this.invitationService = invitationService;
        this.userService = userService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        model.addAttribute("invitations", invitationService.findVisibleTo(me));
        model.addAttribute("now", Instant.now());
        model.addAttribute("currentUser", me);
        return "invitations/list";
    }

    @GetMapping("/new")
    public String form(@AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        if (!model.containsAttribute("form")) {
            InviteForm form = new InviteForm();
            // a group admin has exactly one choice, so make it for them
            form.setGroupId(me.isSuperAdmin() ? null : me.getGroupId());
            model.addAttribute("form", form);
        }
        prepareFormModel(model, me);
        return "invitations/form";
    }

    @PostMapping
    public String invite(@AuthenticationPrincipal PortalUser principal, @Valid @ModelAttribute("form") InviteForm form,
            BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        UserAccount me = userService.getCurrent(principal);
        if (!bindingResult.hasErrors()) {
            try {
                report(invitationService.invite(form.toRequest(), me, baseUrl()), redirectAttributes);
                return "redirect:/invitations";
            } catch (InvitationException | AccessDeniedException e) {
                // a rejected invitation is a message on the form, not a 403 page
                bindingResult.reject("invite.rejected", e.getMessage());
            }
        }
        prepareFormModel(model, me);
        return "invitations/form";
    }

    @PostMapping("/{id}/resend")
    public String resend(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            RedirectAttributes redirectAttributes) {
        UserAccount me = userService.getCurrent(principal);
        try {
            report(invitationService.resend(id, me, baseUrl()), redirectAttributes);
        } catch (InvitationException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/invitations";
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            RedirectAttributes redirectAttributes) {
        UserAccount me = userService.getCurrent(principal);
        try {
            redirectAttributes.addFlashAttribute("success",
                    "Withdrew the invitation for " + invitationService.revoke(id, me).getEmail() + ".");
        } catch (InvitationException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/invitations";
    }

    /**
     * Tells the admin what happened. When no email went out the link is shown
     * once, here, because it is nowhere else: only its hash was stored.
     */
    private static void report(IssuedInvitation issued, RedirectAttributes redirectAttributes) {
        if (issued.emailed()) {
            redirectAttributes.addFlashAttribute("success",
                    "Invitation emailed to " + issued.invitation().getEmail() + ".");
        } else {
            // the invitation is real and usable, but nothing was delivered - a warning
            // rather than a success, since the admin still has something to do
            redirectAttributes.addFlashAttribute("warning", "Invited " + issued.invitation().getEmail()
                    + ", but no email could be sent.");
            redirectAttributes.addFlashAttribute("invitationLink", issued.link());
        }
    }

    /** Where this portal appears to be, for links in the email. */
    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    private void prepareFormModel(Model model, UserAccount me) {
        model.addAttribute("currentUser", me);
        model.addAttribute("roles", invitationService.invitableRoles(me));
        model.addAttribute("groups", invitationService.invitableGroups(me));
    }
}
