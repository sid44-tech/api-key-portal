package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.sidbhat.apikeyportal.sso.SsoConfiguration;
import com.sidbhat.apikeyportal.sso.SsoDetails;

/**
 * Backing object for the single sign-on page.
 */
public class SsoForm {

    private boolean enabled;

    @NotBlank
    @Size(max = 60)
    private String displayName;

    @NotBlank
    @Size(max = 500)
    @Pattern(regexp = "^https://.*", message = "must be an https:// URL")
    private String issuerUri;

    @NotBlank
    @Size(max = 255)
    private String clientId;

    /** Blank on an edit keeps the stored secret; it is never rendered back out. */
    @Size(max = 2000)
    private String clientSecret;

    @Size(max = 300)
    private String scopes = SsoConfiguration.DEFAULT_SCOPES;

    @Size(max = 100)
    private String groupsClaim = SsoConfiguration.DEFAULT_GROUPS_CLAIM;

    @NotBlank(message = "name the group that grants access, so not everyone in the directory gets in")
    @Size(max = 255)
    private String requiredGroup;

    @Size(max = 255)
    private String adminGroup;

    private Long defaultGroupId;

    /** True once a secret is stored, so the form can say "leave blank to keep it". */
    private boolean secretStored;

    public static SsoForm from(SsoConfiguration config) {
        SsoForm form = new SsoForm();
        form.enabled = config.isEnabled();
        form.displayName = config.getDisplayName();
        form.issuerUri = config.getIssuerUri();
        form.clientId = config.getClientId();
        form.scopes = config.getScopes();
        form.groupsClaim = config.getGroupsClaim();
        form.requiredGroup = config.getRequiredGroup();
        form.adminGroup = config.getAdminGroup();
        form.defaultGroupId = config.getDefaultGroupId();
        form.secretStored = true;
        return form;
    }

    @AssertTrue(message = "a client secret is needed the first time you connect a provider")
    public boolean isClientSecretPresentWhenNew() {
        return secretStored || (clientSecret != null && !clientSecret.isBlank());
    }

    @AssertTrue(message = "pick the portal group that people signing in should join")
    public boolean isDefaultGroupChosenWhenEnabled() {
        return !enabled || defaultGroupId != null;
    }

    public SsoDetails toDetails() {
        return new SsoDetails(enabled, displayName, issuerUri, clientId, clientSecret, scopes, groupsClaim,
                requiredGroup, adminGroup, defaultGroupId);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getGroupsClaim() {
        return groupsClaim;
    }

    public void setGroupsClaim(String groupsClaim) {
        this.groupsClaim = groupsClaim;
    }

    public String getRequiredGroup() {
        return requiredGroup;
    }

    public void setRequiredGroup(String requiredGroup) {
        this.requiredGroup = requiredGroup;
    }

    public String getAdminGroup() {
        return adminGroup;
    }

    public void setAdminGroup(String adminGroup) {
        this.adminGroup = adminGroup;
    }

    public Long getDefaultGroupId() {
        return defaultGroupId;
    }

    public void setDefaultGroupId(Long defaultGroupId) {
        this.defaultGroupId = defaultGroupId;
    }

    public boolean isSecretStored() {
        return secretStored;
    }

    public void setSecretStored(boolean secretStored) {
        this.secretStored = secretStored;
    }
}
