package com.sidbhat.apikeyportal.web;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.integration.ServiceIntegration;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationService;
import com.sidbhat.apikeyportal.integration.ServiceProvider;
import com.sidbhat.apikeyportal.integration.google.ServiceAccountKey;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;

@Controller
@RequestMapping("/integrations")
public class ServiceIntegrationController {

    private final ServiceIntegrationService integrationService;
    private final UserAccountService userService;

    public ServiceIntegrationController(ServiceIntegrationService integrationService,
            UserAccountService userService) {
        this.integrationService = integrationService;
        this.userService = userService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        model.addAttribute("integrations", integrationService.findAll(me));
        model.addAttribute("currentUser", me);
        // connecting and removing integrations is a group admin's job; members only use them
        model.addAttribute("canManage", ServiceIntegrationService.canManage(me));
        return "integrations/list";
    }

    @GetMapping("/new")
    public String setup(@RequestParam("provider") ServiceProvider provider,
            @AuthenticationPrincipal PortalUser principal, Model model) {
        // checked here as well as on submit, so a member is turned away at the door
        // rather than after filling in a tenant ID and a client secret
        if (!ServiceIntegrationService.canManage(userService.getCurrent(principal))) {
            throw new AccessDeniedException("Only a group admin can connect a service integration");
        }
        return switch (provider) {
            case AZURE_AD -> {
                if (!model.containsAttribute("form")) {
                    model.addAttribute("form", new AzureIntegrationForm());
                }
                yield "integrations/azure-setup";
            }
            case GOOGLE_CLOUD -> {
                if (!model.containsAttribute("form")) {
                    model.addAttribute("form", new GoogleIntegrationForm());
                }
                yield "integrations/google-setup";
            }
        };
    }

    @PostMapping("/azure-ad")
    public String connectAzure(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") AzureIntegrationForm form,
            BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "integrations/azure-setup";
        }
        try {
            ServiceIntegration integration = integrationService.connect(ServiceProvider.AZURE_AD, form.getName(),
                    form.getTenantId(), form.getClientId(), form.getClientSecret(),
                    userService.getCurrent(principal));
            redirectAttributes.addFlashAttribute("success",
                    "Connected " + integration.getName() + ". The credentials were verified and stored encrypted.");
            return "redirect:/integrations";
        } catch (ProvisioningException e) {
            bindingResult.reject("azure.connect.failed", e.getMessage()
                    + " Double-check the values from steps 3 and 5, and that admin consent in step 4 was granted.");
            return "integrations/azure-setup";
        }
    }

    @PostMapping("/google-cloud")
    public String connectGoogle(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") GoogleIntegrationForm form,
            BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        ServiceAccountKey key = null;
        if (!bindingResult.hasErrors()) {
            try {
                key = ServiceAccountKey.parse(form.getServiceAccountJson());
            } catch (IllegalArgumentException e) {
                bindingResult.rejectValue("serviceAccountJson", "google.key.invalid", e.getMessage());
            }
        }
        if (bindingResult.hasErrors()) {
            return "integrations/google-setup";
        }
        try {
            ServiceIntegration integration = integrationService.connect(ServiceProvider.GOOGLE_CLOUD,
                    form.getName(), key.projectId(), key.clientEmail(), key.privateKey(),
                    userService.getCurrent(principal));
            redirectAttributes.addFlashAttribute("success",
                    "Connected " + integration.getName() + ". The credentials were verified and stored encrypted.");
            return "redirect:/integrations";
        } catch (ProvisioningException e) {
            bindingResult.reject("google.connect.failed", e.getMessage()
                    + " Double-check the roles from step 2 were granted and the JSON key from step 3 is current.");
            return "integrations/google-setup";
        }
    }

    @PostMapping("/{id}/test")
    public String test(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            RedirectAttributes redirectAttributes) {
        try {
            ServiceIntegration integration = integrationService.test(id, userService.getCurrent(principal));
            redirectAttributes.addFlashAttribute("success",
                    "Connection to " + integration.getName() + " works.");
        } catch (ProvisioningException e) {
            redirectAttributes.addFlashAttribute("error", "Connection test failed: " + e.getMessage());
        }
        return "redirect:/integrations";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            RedirectAttributes redirectAttributes) {
        ServiceIntegration integration = integrationService.delete(id, userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", "Removed " + integration.getName()
                + ". Keys created through it are untouched, and nothing was deleted in the provider.");
        return "redirect:/integrations";
    }
}
