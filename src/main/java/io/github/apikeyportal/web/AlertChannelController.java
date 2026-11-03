package io.github.apikeyportal.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.github.apikeyportal.alerts.AlertChannel;
import io.github.apikeyportal.alerts.AlertChannelService;
import io.github.apikeyportal.alerts.AlertException;
import io.github.apikeyportal.alerts.ChannelProvider;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountService;

/**
 * Connecting a group's chat channel - {@code /integrations/alerts/teams},
 * {@code /integrations/alerts/slack}. Group admins and super admins, like the
 * provider integrations; the route is restricted in {@code SecurityConfig}.
 */
@Controller
@RequestMapping("/integrations/alerts/{provider}")
public class AlertChannelController {

    private final AlertChannelService channels;
    private final UserAccountService userService;

    public AlertChannelController(AlertChannelService channels, UserAccountService userService) {
        this.channels = channels;
        this.userService = userService;
    }

    /** The path segment as a provider, or a 404 - there is no page for a product the portal does not know. */
    @ModelAttribute("provider")
    public ChannelProvider provider(@PathVariable("provider") String path) {
        try {
            return ChannelProvider.fromPath(path);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping
    public String setup(@ModelAttribute("provider") ChannelProvider provider,
            @AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", channels.find(me, provider).map(AlertChannelForm::from)
                    .orElseGet(AlertChannelForm::new));
        }
        prepareModel(model, provider, me);
        return "integrations/alert-setup";
    }

    @PostMapping
    public String save(@ModelAttribute("provider") ChannelProvider provider,
            @AuthenticationPrincipal PortalUser principal, @Valid @ModelAttribute("form") AlertChannelForm form,
            BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        UserAccount me = userService.getCurrent(principal);
        if (!bindingResult.hasErrors()) {
            try {
                AlertChannel saved = channels.save(provider, form.getName(), form.getWebhookUrl(), form.isEnabled(),
                        form.isNotifyExpiries(), form.isNotifyRotationFailures(), form.isNotifyLockouts(), me);
                redirectAttributes.addFlashAttribute("success", "Connected " + saved.getName() + " on "
                        + provider.getLabel() + " - a test card was posted to it just now.");
                return "redirect:/integrations";
            } catch (AlertException e) {
                // the service saying no is a message on the form, not a 500
                bindingResult.reject("alerts.failed", e.getMessage());
            }
        }
        prepareModel(model, provider, me);
        return "integrations/alert-setup";
    }

    @PostMapping("/test")
    public String test(@ModelAttribute("provider") ChannelProvider provider,
            @AuthenticationPrincipal PortalUser principal, RedirectAttributes redirectAttributes) {
        try {
            channels.sendTest(provider, userService.getCurrent(principal));
            redirectAttributes.addFlashAttribute("success", "A test card was posted to the " + provider.getLabel()
                    + " channel.");
        } catch (AlertException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/integrations";
    }

    @PostMapping("/delete")
    public String remove(@ModelAttribute("provider") ChannelProvider provider,
            @AuthenticationPrincipal PortalUser principal, RedirectAttributes redirectAttributes) {
        channels.remove(provider, userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", "The " + provider.getLabel()
                + " channel was disconnected. Nothing changes in " + provider.getLabel() + ".");
        return "redirect:/integrations";
    }

    private void prepareModel(Model model, ChannelProvider provider, UserAccount me) {
        model.addAttribute("groupName", me.getGroup() == null ? null : me.getGroup().getName());
        model.addAttribute("connected", channels.find(me, provider).isPresent());
    }
}
