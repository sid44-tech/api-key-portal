package com.sidbhat.apikeyportal.web;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * Backing object for the guided walk-throughs that store a key created by hand in a
 * provider's console (GitHub, Anthropic, Atlassian). The pasted value is verified
 * against the provider before anything is saved.
 */
public class GuidedKeyForm {

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank(message = "must be filled in - the steps above show how to create it")
    @Size(max = 4096)
    private String keyValue;

    /**
     * The account the token belongs to, where the provider's token is only half
     * of the credential - Atlassian tokens authenticate as email plus token.
     * Unused by the providers whose token stands alone, so the requirement is
     * enforced by the walk-through that needs it rather than by an annotation.
     */
    @Email(message = "must be a valid email address")
    @Size(max = 255)
    private String accountEmail;

    /** Optional; GitHub reports expiry itself, which takes precedence when present. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expiresOn;

    @Size(max = 700)
    private String notes;

    public String getAccountEmail() {
        return accountEmail;
    }

    public void setAccountEmail(String accountEmail) {
        this.accountEmail = accountEmail == null ? null : accountEmail.trim();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public void setKeyValue(String keyValue) {
        this.keyValue = keyValue;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public void setExpiresOn(LocalDate expiresOn) {
        this.expiresOn = expiresOn;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
