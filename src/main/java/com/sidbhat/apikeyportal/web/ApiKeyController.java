package com.sidbhat.apikeyportal.web;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sidbhat.apikeyportal.activity.KeyOrigin;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationService;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.key.ApiKeySort;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;

@Controller
@RequestMapping("/keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ServiceIntegrationService integrationService;
    private final UserAccountService userService;

    public ApiKeyController(ApiKeyService apiKeyService, ServiceIntegrationService integrationService,
            UserAccountService userService) {
        this.apiKeyService = apiKeyService;
        this.integrationService = integrationService;
        this.userService = userService;
    }

    /**
     * The keys of everyone in the signed-in user's group. Values stay hidden for
     * anyone but the owner; {@code owner} narrows the list to one person.
     */
    @GetMapping
    public String list(@AuthenticationPrincipal PortalUser principal,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "owner", required = false) Long ownerId,
            @RequestParam(name = "sort", defaultValue = "SERVICE") ApiKeySort sort,
            @RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        UserAccount me = userService.getCurrent(principal);
        Page<ApiKey> keys = apiKeyService.search(me, query, service, ownerId, sort, page);
        model.addAttribute("keys", keys.getContent());
        model.addAttribute("page", keys);
        model.addAttribute("summary", apiKeyService.getSummary(me));
        model.addAttribute("services", apiKeyService.findServiceNames(me));
        model.addAttribute("people", userService.findVisibleTo(me));
        model.addAttribute("sorts", ApiKeySort.values());
        model.addAttribute("sort", sort);
        model.addAttribute("q", query);
        model.addAttribute("service", service);
        model.addAttribute("owner", ownerId);
        model.addAttribute("currentUser", me);
        return "keys/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        model.addAttribute("key", apiKeyService.getById(id, me));
        model.addAttribute("currentUser", me);
        return "keys/detail";
    }

    @GetMapping("/new")
    public String newKey(@AuthenticationPrincipal PortalUser principal, Model model) {
        model.addAttribute("form", new ApiKeyForm());
        // offers "create it in the provider instead" when the group has integrations set up
        model.addAttribute("integrations", integrationService.findAll(userService.getCurrent(principal)));
        return "keys/form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") ApiKeyForm form, BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "keys/form";
        }
        ApiKey key = apiKeyService.create(form.toDetails(), userService.getCurrent(principal), KeyOrigin.manual());
        redirectAttributes.addFlashAttribute("success", "Added " + key.getServiceName() + " / " + key.getName() + ".");
        return "redirect:/keys";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        ApiKey key = apiKeyService.getById(id, me);
        if (!key.isOwnedBy(me)) {
            // the group can see the key, but only its owner can change it
            return "redirect:/keys/" + id;
        }
        model.addAttribute("form", ApiKeyForm.from(key));
        return "keys/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") ApiKeyForm form, BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {
        form.setId(id);
        if (bindingResult.hasErrors()) {
            return "keys/form";
        }
        apiKeyService.update(id, form.toDetails(), userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", "Changes saved.");
        return "redirect:/keys/" + id;
    }

    /**
     * The "Mute expiration notifications" switch. Also where the link in a reminder
     * email lands, after signing in, so muting is one click from the email.
     */
    @PostMapping("/{id}/notifications")
    public String setNotifications(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            @RequestParam(value = "muted", defaultValue = "false") boolean muted,
            RedirectAttributes redirectAttributes) {
        apiKeyService.setExpiryNotificationsMuted(id, muted, userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", muted
                ? "You won't be emailed about this key expiring. Other keys are unaffected."
                : "You'll be emailed again as this key approaches its expiry.");
        return "redirect:/keys/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            RedirectAttributes redirectAttributes) {
        ApiKey key = apiKeyService.delete(id, userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", "Deleted " + key.getServiceName() + " / " + key.getName() + ".");
        return "redirect:/keys";
    }
}
