package com.sidbhat.apikeyportal.web;

import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sidbhat.apikeyportal.activity.KeyOrigin;
import com.sidbhat.apikeyportal.guided.AnthropicClient;
import com.sidbhat.apikeyportal.guided.GitHubClient;
import com.sidbhat.apikeyportal.guided.GitHubClient.TokenInfo;
import com.sidbhat.apikeyportal.guided.GuidedProvider;
import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyDetails;
import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * Guided walk-throughs for providers whose keys must be created by hand in their
 * console (no API for it exists). The wizard explains the manual steps, then the
 * pasted key is verified live against the provider before it is stored - so a
 * saved key is known to have worked at least once.
 */
@Controller
@RequestMapping("/keys/guided")
public class GuidedKeyController {

    private final ApiKeyService apiKeyService;
    private final GitHubClient gitHubClient;
    private final AnthropicClient anthropicClient;
    private final UserAccountService userService;

    public GuidedKeyController(ApiKeyService apiKeyService, GitHubClient gitHubClient,
            AnthropicClient anthropicClient, UserAccountService userService) {
        this.apiKeyService = apiKeyService;
        this.gitHubClient = gitHubClient;
        this.anthropicClient = anthropicClient;
        this.userService = userService;
    }

    @GetMapping("/{provider}")
    public String wizard(@PathVariable("provider") String providerSlug, Model model) {
        GuidedProvider provider = resolve(providerSlug);
        model.addAttribute("provider", provider);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new GuidedKeyForm());
        }
        return template(provider);
    }

    @PostMapping("/{provider}")
    public String store(@PathVariable("provider") String providerSlug,
            @AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") GuidedKeyForm form,
            BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        GuidedProvider provider = resolve(providerSlug);
        model.addAttribute("provider", provider);
        if (bindingResult.hasErrors()) {
            return template(provider);
        }
        UserAccount me = userService.getCurrent(principal);
        try {
            ApiKey key = switch (provider) {
                case GITHUB -> storeGitHub(form, me);
                case ANTHROPIC -> storeAnthropic(form, me);
            };
            redirectAttributes.addFlashAttribute("success", "Verified the " + provider.getLabel() + " "
                    + provider.getCredentialName().toLowerCase() + " and stored it encrypted.");
            return "redirect:/keys/" + key.getId();
        } catch (ProvisioningException e) {
            bindingResult.reject("guided.verify.failed", e.getMessage());
            return template(provider);
        }
    }

    private ApiKey storeGitHub(GuidedKeyForm form, UserAccount owner) {
        TokenInfo info = gitHubClient.verifyToken(form.getKeyValue().trim());
        // GitHub knows the token's real expiry; prefer it over what the user typed
        LocalDate expiresOn = info.expiresOn() != null ? info.expiresOn() : form.getExpiresOn();
        return apiKeyService.create(new ApiKeyDetails(form.getName(), GuidedProvider.GITHUB.getServiceName(),
                form.getKeyValue(), LocalDate.now(), expiresOn, gitHubNotes(form.getNotes(), info)),
                owner, KeyOrigin.guidedBy(GuidedProvider.GITHUB.getLabel()));
    }

    private ApiKey storeAnthropic(GuidedKeyForm form, UserAccount owner) {
        anthropicClient.verifyKey(form.getKeyValue().trim());
        return apiKeyService.create(new ApiKeyDetails(form.getName(), GuidedProvider.ANTHROPIC.getServiceName(),
                form.getKeyValue(), LocalDate.now(), form.getExpiresOn(), form.getNotes()),
                owner, KeyOrigin.guidedBy(GuidedProvider.ANTHROPIC.getLabel()));
    }

    private static String gitHubNotes(String userNotes, TokenInfo info) {
        StringBuilder notes = new StringBuilder();
        if (StringUtils.hasText(userNotes)) {
            notes.append(userNotes.trim()).append("\n\n");
        }
        notes.append("Verified against GitHub on save");
        if (info.login() != null) {
            notes.append(" as ").append(info.login());
        }
        notes.append('.');
        if (info.scopes() != null) {
            notes.append("\nScopes: ").append(info.scopes());
        }
        return notes.toString();
    }

    /** Maps the URL slug to a provider; unknown slugs 404 like any other missing page. */
    private static GuidedProvider resolve(String slug) {
        try {
            return GuidedProvider.valueOf(slug.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static String template(GuidedProvider provider) {
        return switch (provider) {
            case GITHUB -> "keys/guided-github";
            case ANTHROPIC -> "keys/guided-anthropic";
        };
    }
}
