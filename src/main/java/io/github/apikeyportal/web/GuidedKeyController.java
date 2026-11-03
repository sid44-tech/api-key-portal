package io.github.apikeyportal.web;

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

import io.github.apikeyportal.activity.KeyOrigin;
import io.github.apikeyportal.guided.AnthropicClient;
import io.github.apikeyportal.guided.AtlassianClient;
import io.github.apikeyportal.guided.AtlassianClient.AccountInfo;
import io.github.apikeyportal.guided.GitHubClient;
import io.github.apikeyportal.guided.GitHubClient.TokenInfo;
import io.github.apikeyportal.guided.GuidedProvider;
import io.github.apikeyportal.guided.OpenAiClient;
import io.github.apikeyportal.guided.StripeClient;
import io.github.apikeyportal.integration.ProvisioningException;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.key.ApiKeyDetails;
import io.github.apikeyportal.key.ApiKeyService;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountService;

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
    private final AtlassianClient atlassianClient;
    private final OpenAiClient openAiClient;
    private final StripeClient stripeClient;
    private final UserAccountService userService;
    private final AuditLog audit;

    public GuidedKeyController(ApiKeyService apiKeyService, GitHubClient gitHubClient,
            AnthropicClient anthropicClient, AtlassianClient atlassianClient, OpenAiClient openAiClient,
            StripeClient stripeClient, UserAccountService userService, AuditLog audit) {
        this.apiKeyService = apiKeyService;
        this.gitHubClient = gitHubClient;
        this.anthropicClient = anthropicClient;
        this.atlassianClient = atlassianClient;
        this.openAiClient = openAiClient;
        this.stripeClient = stripeClient;
        this.userService = userService;
        this.audit = audit;
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
        // the email half is only asked for where the token is useless without it
        if (provider == GuidedProvider.ATLASSIAN && !StringUtils.hasText(form.getAccountEmail())) {
            bindingResult.rejectValue("accountEmail", "guided.email.required",
                    "must be filled in - an Atlassian token only works together with its account email");
            return template(provider);
        }
        UserAccount me = userService.getCurrent(principal);
        try {
            ApiKey key = switch (provider) {
                case GITHUB -> storeGitHub(form, me);
                case ANTHROPIC -> storeAnthropic(form, me);
                case ATLASSIAN -> storeAtlassian(form, me);
                case OPENAI -> storeOpenAi(form, me);
                case STRIPE -> storeStripe(form, me);
            };
            redirectAttributes.addFlashAttribute("success", "Verified the " + provider.getLabel() + " "
                    + provider.getCredentialName().toLowerCase() + " and stored it encrypted.");
            return "redirect:/keys/" + key.getId();
        } catch (ProvisioningException e) {
            // The key the person pasted was rejected by the provider. Worth
            // recording: a run of these is somebody pasting the wrong thing, or
            // a provider that has stopped answering, and neither shows up
            // anywhere else - a key that fails verification is never stored, so
            // it leaves no history entry behind.
            audit.refused("guided_key_verification", me.getEmail(), provider.getLabel(), e.getMessage());
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

    private ApiKey storeOpenAi(GuidedKeyForm form, UserAccount owner) {
        openAiClient.verifyKey(form.getKeyValue().trim());
        return apiKeyService.create(new ApiKeyDetails(form.getName(), GuidedProvider.OPENAI.getServiceName(),
                form.getKeyValue(), LocalDate.now(), form.getExpiresOn(), form.getNotes()),
                owner, KeyOrigin.guidedBy(GuidedProvider.OPENAI.getLabel()));
    }

    private ApiKey storeStripe(GuidedKeyForm form, UserAccount owner) {
        StripeClient.AccountInfo account = stripeClient.verifyKey(form.getKeyValue().trim());
        return apiKeyService.create(new ApiKeyDetails(form.getName(), GuidedProvider.STRIPE.getServiceName(),
                form.getKeyValue(), LocalDate.now(), form.getExpiresOn(),
                stripeNotes(form.getNotes(), account)),
                owner, KeyOrigin.guidedBy(GuidedProvider.STRIPE.getLabel()));
    }

    /**
     * Records which Stripe account the key is for and, crucially, whether it is a
     * live key: test and live keys sit side by side in a list looking identical,
     * and only one of them moves real money.
     */
    private static String stripeNotes(String userNotes, StripeClient.AccountInfo account) {
        StringBuilder notes = new StringBuilder();
        if (StringUtils.hasText(userNotes)) {
            notes.append(userNotes.trim()).append("\n\n");
        }
        notes.append("Verified against Stripe on save.");
        if (StringUtils.hasText(account.id())) {
            notes.append("\nAccount: ").append(account.id());
            if (account.businessProfile() != null && StringUtils.hasText(account.businessProfile().name())) {
                notes.append(" (").append(account.businessProfile().name()).append(')');
            }
        }
        if (account.livemode() != null) {
            notes.append("\nMode: ").append(account.livemode() ? "LIVE - this key moves real money" : "test");
        }
        return notes.toString();
    }

    private ApiKey storeAnthropic(GuidedKeyForm form, UserAccount owner) {
        anthropicClient.verifyKey(form.getKeyValue().trim());
        return apiKeyService.create(new ApiKeyDetails(form.getName(), GuidedProvider.ANTHROPIC.getServiceName(),
                form.getKeyValue(), LocalDate.now(), form.getExpiresOn(), form.getNotes()),
                owner, KeyOrigin.guidedBy(GuidedProvider.ANTHROPIC.getLabel()));
    }

    /**
     * Stores the token alone as the value, with the account email in the notes.
     * The email is not a secret - it is the person's own address, and it is the
     * one thing someone holding the token still needs to use it, so it belongs
     * where the rest of the "how do I use this" context lives.
     */
    private ApiKey storeAtlassian(GuidedKeyForm form, UserAccount owner) {
        AccountInfo account = atlassianClient.verifyToken(form.getAccountEmail(),
                form.getKeyValue().trim());
        return apiKeyService.create(new ApiKeyDetails(form.getName(), GuidedProvider.ATLASSIAN.getServiceName(),
                form.getKeyValue(), LocalDate.now(), form.getExpiresOn(),
                atlassianNotes(form.getNotes(), form.getAccountEmail(), account)),
                owner, KeyOrigin.guidedBy(GuidedProvider.ATLASSIAN.getLabel()));
    }

    private static String atlassianNotes(String userNotes, String accountEmail, AccountInfo account) {
        StringBuilder notes = new StringBuilder();
        if (StringUtils.hasText(userNotes)) {
            notes.append(userNotes.trim()).append("\n\n");
        }
        notes.append("Verified against Atlassian on save");
        if (StringUtils.hasText(account.name())) {
            notes.append(" as ").append(account.name());
        }
        notes.append(".\nUse as HTTP Basic auth: ").append(accountEmail).append(" + this token.");
        return notes.toString();
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
            case ATLASSIAN -> "keys/guided-atlassian";
            case OPENAI -> "keys/guided-openai";
            case STRIPE -> "keys/guided-stripe";
        };
    }
}
