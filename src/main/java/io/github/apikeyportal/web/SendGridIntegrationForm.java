package io.github.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.github.apikeyportal.integration.sendgrid.SendGridAccess;

/** The values the SendGrid setup wizard collects. */
public class SendGridIntegrationForm {

    @NotBlank(message = "Give the integration a name")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "Enter the API key")
    @Size(max = 256)
    private String apiKey;

    @NotNull(message = "Choose what the keys may do")
    private SendGridAccess access = SendGridAccess.MAIL_SEND;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? null : apiKey.trim();
    }

    public SendGridAccess getAccess() {
        return access;
    }

    public void setAccess(SendGridAccess access) {
        this.access = access;
    }

    @Override
    public String toString() {
        // never include the key here, this ends up in logs
        return "SendGridIntegrationForm{name='" + name + "', access=" + access + "}";
    }
}
