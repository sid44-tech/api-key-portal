package com.sidbhat.apikeyportal.web;

import jakarta.validation.Valid;

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

@Controller
@RequestMapping("/integrations")
public class ServiceIntegrationController {

    private final ServiceIntegrationService integrationService;

    public ServiceIntegrationController(ServiceIntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("integrations", integrationService.findAll());
        model.addAttribute("providers", ServiceProvider.values());
        return "integrations/list";
    }

    @GetMapping("/new")
    public String setup(@RequestParam("provider") ServiceProvider provider, Model model) {
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
    public String connectAzure(@Valid @ModelAttribute("form") AzureIntegrationForm form,
            BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "integrations/azure-setup";
        }
        try {
            ServiceIntegration integration = integrationService.connect(ServiceProvider.AZURE_AD, form.getName(),
                    form.getTenantId(), form.getClientId(), form.getClientSecret());
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
    public String connectGoogle(@Valid @ModelAttribute("form") GoogleIntegrationForm form,
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
                    form.getName(), key.projectId(), key.clientEmail(), key.privateKey());
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
    public String test(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            ServiceIntegration integration = integrationService.test(id);
            redirectAttributes.addFlashAttribute("success",
                    "Connection to " + integration.getName() + " works.");
        } catch (ProvisioningException e) {
            redirectAttributes.addFlashAttribute("error", "Connection test failed: " + e.getMessage());
        }
        return "redirect:/integrations";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        ServiceIntegration integration = integrationService.delete(id);
        redirectAttributes.addFlashAttribute("success", "Removed " + integration.getName()
                + ". Keys created through it are untouched, and nothing was deleted in the provider.");
        return "redirect:/integrations";
    }
}
