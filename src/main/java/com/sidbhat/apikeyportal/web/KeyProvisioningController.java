package com.sidbhat.apikeyportal.web;

import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sidbhat.apikeyportal.integration.KeyProvisioningService;
import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.integration.SecretValidity;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationService;
import com.sidbhat.apikeyportal.key.ApiKey;

/**
 * Creates a key directly in a connected provider instead of pasting one in manually.
 */
@Controller
@RequestMapping("/keys/provision")
public class KeyProvisioningController {

    private final ServiceIntegrationService integrationService;
    private final KeyProvisioningService provisioningService;

    public KeyProvisioningController(ServiceIntegrationService integrationService,
            KeyProvisioningService provisioningService) {
        this.integrationService = integrationService;
        this.provisioningService = provisioningService;
    }

    @GetMapping
    public String form(@RequestParam("integration") Long integrationId, Model model) {
        ProvisionKeyForm form = new ProvisionKeyForm();
        form.setIntegrationId(integrationId);
        model.addAttribute("form", form);
        prepareModel(model, integrationId);
        return "keys/provision";
    }

    @PostMapping
    public String provision(@Valid @ModelAttribute("form") ProvisionKeyForm form, BindingResult bindingResult,
            Model model, RedirectAttributes redirectAttributes) {
        prepareModel(model, form.getIntegrationId());
        if (bindingResult.hasErrors()) {
            return "keys/provision";
        }
        try {
            ApiKey key = provisioningService.provision(form.getIntegrationId(), form.getName(), form.getValidity(),
                    form.getNotes());
            redirectAttributes.addFlashAttribute("success", "Created " + key.getName()
                    + " and stored the new client secret. The client ID is in the notes below.");
            return "redirect:/keys/" + key.getId();
        } catch (ProvisioningException e) {
            bindingResult.reject("provision.failed", e.getMessage());
            return "keys/provision";
        }
    }

    private void prepareModel(Model model, Long integrationId) {
        model.addAttribute("integration", integrationService.getById(integrationId));
        model.addAttribute("validities", SecretValidity.values());
    }
}
