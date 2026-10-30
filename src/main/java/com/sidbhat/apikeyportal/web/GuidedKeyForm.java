package com.sidbhat.apikeyportal.web;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * Backing object for the guided walk-throughs that store a key created by hand in a
 * provider's console (GitHub, Anthropic). The pasted value is verified against the
 * provider before anything is saved.
 */
public class GuidedKeyForm {

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank(message = "must be filled in - the steps above show how to create it")
    @Size(max = 4096)
    private String keyValue;

    /** Optional; GitHub reports expiry itself, which takes precedence when present. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expiresOn;

    @Size(max = 700)
    private String notes;

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
