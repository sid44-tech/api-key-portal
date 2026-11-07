package io.github.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The values the Twilio setup wizard collects: which account, and the Main API
 * key (or Auth Token) the portal manages keys with.
 */
public class TwilioIntegrationForm {

    @NotBlank(message = "Give the integration a name")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "Enter the Account SID")
    @Pattern(regexp = "AC[0-9a-fA-F]{32}", message = "An Account SID is AC followed by 32 hex characters")
    private String accountSid;

    /** A Main API key's SID; or the Account SID again, for an Auth Token. */
    @NotBlank(message = "Enter the API key SID")
    @Pattern(regexp = "(SK|AC)[0-9a-fA-F]{32}",
            message = "An API key SID is SK followed by 32 hex characters")
    private String apiKeySid;

    @NotBlank(message = "Enter the API key secret")
    @Size(max = 256)
    private String apiKeySecret;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAccountSid() {
        return accountSid;
    }

    public void setAccountSid(String accountSid) {
        this.accountSid = accountSid == null ? null : accountSid.trim();
    }

    public String getApiKeySid() {
        return apiKeySid;
    }

    public void setApiKeySid(String apiKeySid) {
        this.apiKeySid = apiKeySid == null ? null : apiKeySid.trim();
    }

    public String getApiKeySecret() {
        return apiKeySecret;
    }

    public void setApiKeySecret(String apiKeySecret) {
        this.apiKeySecret = apiKeySecret == null ? null : apiKeySecret.trim();
    }

    @Override
    public String toString() {
        // never include the secret here, this ends up in logs
        return "TwilioIntegrationForm{name='" + name + "', accountSid='" + accountSid + "'}";
    }
}
