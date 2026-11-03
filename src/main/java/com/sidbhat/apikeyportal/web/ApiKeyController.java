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
import com.sidbhat.apikeyportal.rotation.KeyRotationService;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;

@Controller
@RequestMapping("/keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ServiceIntegrationService integrationService;
    private final UserAccountService userService;
    private final KeyRotationService rotationService;

    public ApiKeyController(ApiKeyService apiKeyService, ServiceIntegrationService integrationService,
            UserAccountService userService, KeyRotationService rotationService) {
        this.apiKeyService = apiKeyService;
        this.integrationService = integrationService;
        this.userService = userService;
        this.rotationService = rotationService;
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
            @RequestParam(name = "showExpired", defaultValue = "false") boolean showExpired,
            @RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        UserAccount me = userService.getCurrent(principal);
        Page<ApiKey> keys = apiKeyService.search(me, query, service, ownerId, sort, showExpired, page);
        model.addAttribute("keys", keys.getContent());
        // which of the listed keys already have a replacement minted, so an
        // expiring key can point at its successor
        model.addAttribute("replacements", apiKeyService.findReplacements(keys.getContent()));
        model.addAttribute("showExpired", showExpired);
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
        ApiKey key = apiKeyService.getById(id, me);
        model.addAttribute("key", key);
        model.addAttribute("replacement", apiKeyService.findReplacement(id));
        model.addAttribute("rotationSupported", rotationService.supportsRotation(key));
        // the two kinds of rotation promise different things; the page must not
        // promise an overlap the provider will not give
        model.addAttribute("rotationOverlaps", rotationService.rotationOverlaps(key));
        // ...and where the portal will delete this one at the provider afterwards,
        // the page has to say so - and whether that is tidying or the only thing
        // that makes the expiry real
        model.addAttribute("rotationRevokes", rotationService.deletesReplaced(key));
        model.addAttribute("providerNeverExpires", rotationService.providerNeverExpires(key));
        model.addAttribute("providerRotationOn", rotationService.isEnabledForProviderOf(key, me));
        model.addAttribute("currentUser", me);
        return "keys/detail";
    }

    /**
     * The per-key say on automatic replacement: an empty value hands the key back
     * to the owner's setting for the provider, true or false pins it.
     */
    @PostMapping("/{id}/rotation")
    public String setRotation(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            @RequestParam(value = "autoRotate", required = false) String autoRotate,
            RedirectAttributes redirectAttributes) {
        Boolean override = (autoRotate == null || autoRotate.isBlank()) ? null : Boolean.valueOf(autoRotate);
        apiKeyService.setAutoRotate(id, override, userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", override == null
                ? "This key follows your provider setting for automatic replacement again."
                : (override ? "This key will be replaced automatically before it expires."
                        : "This key won't be replaced automatically."));
        return "redirect:/keys/" + id;
    }

    @GetMapping("/new")
    public String newKey(@AuthenticationPrincipal PortalUser principal, Model model) {
        model.addAttribute("form", new ApiKeyForm());
        // offers "create it in the provider instead" - only through integrations
        // that are switched on; paused ones stay visible on the integrations page
        model.addAttribute("integrations", integrationService.findUsable(userService.getCurrent(principal)));
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
