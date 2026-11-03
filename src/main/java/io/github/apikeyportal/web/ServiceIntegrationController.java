package io.github.apikeyportal.web;

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

import io.github.apikeyportal.integration.ProvisioningException;
import io.github.apikeyportal.integration.ServiceIntegration;
import io.github.apikeyportal.integration.ServiceIntegrationService;
import io.github.apikeyportal.integration.ServiceProvider;
import io.github.apikeyportal.integration.google.ServiceAccountKey;
import io.github.apikeyportal.alerts.AlertChannelService;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountService;

@Controller
@RequestMapping("/integrations")
public class ServiceIntegrationController {

    private final ServiceIntegrationService integrationService;
    private final UserAccountService userService;
    private final AlertChannelService alertChannels;

    public ServiceIntegrationController(ServiceIntegrationService integrationService,
            UserAccountService userService,
            AlertChannelService alertChannels) {
        this.integrationService = integrationService;
        this.userService = userService;
        this.alertChannels = alertChannels;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        model.addAttribute("integrations", integrationService.findAll(me));
        java.util.List<io.github.apikeyportal.alerts.AlertChannel> channels = alertChannels.findForGroupOf(me);
        model.addAttribute("alertChannels", channels);
        // a card to connect each product the group has not connected yet
        model.addAttribute("unconnectedProviders", java.util.Arrays.stream(
                io.github.apikeyportal.alerts.ChannelProvider.values())
                .filter(p -> channels.stream().noneMatch(c -> c.getProvider() == p)).toList());
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
            case AWS_IAM -> {
                if (!model.containsAttribute("form")) {
                    model.addAttribute("form", new AwsIntegrationForm());
                }
                yield "integrations/aws-setup";
            }
            case GITLAB -> {
                if (!model.containsAttribute("form")) {
                    model.addAttribute("form", new GitLabIntegrationForm());
                }
                yield "integrations/gitlab-setup";
            }
            case CLOUDFLARE -> {
                if (!model.containsAttribute("form")) {
                    model.addAttribute("form", new CloudflareIntegrationForm());
                }
                yield "integrations/cloudflare-setup";
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

    @PostMapping("/aws")
    public String connectAws(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") AwsIntegrationForm form,
            BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "integrations/aws-setup";
        }
        try {
            ServiceIntegration integration = integrationService.connect(ServiceProvider.AWS_IAM, form.getName(),
                    form.getAccountId(), form.getAccessKeyId(), form.getSecretAccessKey(),
                    userService.getCurrent(principal));
            redirectAttributes.addFlashAttribute("success",
                    "Connected " + integration.getName() + ". The credentials were verified and stored encrypted.");
            return "redirect:/integrations";
        } catch (ProvisioningException e) {
            bindingResult.reject("aws.connect.failed", e.getMessage()
                    + " Double-check the access key pair from step 3 and the account ID from step 4.");
            return "integrations/aws-setup";
        }
    }

    @PostMapping("/gitlab")
    public String connectGitLab(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") GitLabIntegrationForm form,
            BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "integrations/gitlab-setup";
        }
        try {
            ServiceIntegration integration = integrationService.connect(ServiceProvider.GITLAB, form.getName(),
                    form.getBaseUrl(), form.getProject(), form.getToken(), userService.getCurrent(principal));
            redirectAttributes.addFlashAttribute("success",
                    "Connected " + integration.getName() + ". The credentials were verified and stored encrypted.");
            return "redirect:/integrations";
        } catch (ProvisioningException e) {
            bindingResult.reject("gitlab.connect.failed", e.getMessage()
                    + " Double-check the URL, the project from step 1 and the token from step 2.");
            return "integrations/gitlab-setup";
        }
    }

    @PostMapping("/cloudflare")
    public String connectCloudflare(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") CloudflareIntegrationForm form,
            BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "integrations/cloudflare-setup";
        }
        try {
            // template token id doubles as the integration's "tenant": it is the
            // one provider-side thing the integration revolves around
            ServiceIntegration integration = integrationService.connect(ServiceProvider.CLOUDFLARE,
                    form.getName(), form.getTemplateTokenId(), form.getTemplateTokenId(), form.getApiToken(),
                    userService.getCurrent(principal));
            redirectAttributes.addFlashAttribute("success",
                    "Connected " + integration.getName() + ". The credentials were verified and stored encrypted.");
            return "redirect:/integrations";
        } catch (ProvisioningException e) {
            bindingResult.reject("cloudflare.connect.failed", e.getMessage()
                    + " Double-check the token from step 1 and the template token ID from step 2.");
            return "integrations/cloudflare-setup";
        }
    }

    /**
     * The group-level switch on one integration. Authorisation lives in the
     * service (super admin: any group; group admin: their own), same as test
     * and remove.
     */
    @PostMapping("/{id}/enabled")
    public String setEnabled(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            @RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
            RedirectAttributes redirectAttributes) {
        ServiceIntegration integration = integrationService.setEnabled(id, enabled,
                userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", enabled
                ? integration.getName() + " is back on. Everyone in the group can create keys through it again."
                : integration.getName() + " is switched off. Nobody in the group can create keys through it "
                        + "until it's switched back on; the stored credential and existing keys are untouched.");
        return "redirect:/integrations";
    }

    @PostMapping("/{id}/delete-replaced")
    public String setDeleteReplacedKeys(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            @RequestParam(value = "delete", defaultValue = "false") boolean delete,
            RedirectAttributes redirectAttributes) {
        ServiceIntegration integration = integrationService.setDeleteReplacedKeys(id, delete,
                userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", delete
                ? "Keys replaced through " + integration.getName() + " will be deleted at "
                        + integration.getProvider().getLabel() + " once they expire."
                : "Keys replaced through " + integration.getName() + " will be left at "
                        + integration.getProvider().getLabel() + " when they expire."
                        + (integration.getProvider().revokedByPortal()
                                ? " Note that " + integration.getProvider().getLabel()
                                        + " never expires them itself, so they keep working there." : ""));
        return "redirect:/integrations";
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
