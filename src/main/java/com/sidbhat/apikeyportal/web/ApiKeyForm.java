package com.sidbhat.apikeyportal.web;

import java.time.LocalDate;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PastOrPresent;
import javax.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyDetails;

/**
 * Backing object for the add / edit key form.
 */
public class ApiKeyForm {

    /** {@code null} when adding a new key. */
    private Long id;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 100)
    private String serviceName;

    /** Required for new keys. When editing, blank means keep the current value. */
    @Size(max = 1024)
    private String keyValue;

    @NotNull
    @PastOrPresent
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdOn = LocalDate.now();

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expiresOn;

    public static ApiKeyForm from(ApiKey key) {
        ApiKeyForm form = new ApiKeyForm();
        form.id = key.getId();
        form.name = key.getName();
        form.serviceName = key.getServiceName();
        form.createdOn = key.getCreatedOn();
        form.expiresOn = key.getExpiresOn();
        form.notes = key.getNotes();
        // keyValue intentionally left blank, it is never rendered into the page
        return form;
    }

    public ApiKeyDetails toDetails() {
        return new ApiKeyDetails(name, serviceName, keyValue, createdOn, expiresOn, notes);
    }

    public boolean isNew() {
        return id == null;
    }

    @AssertTrue(message = "must not be blank")
    public boolean isKeyValuePresentForNewKey() {
        return !isNew() || (keyValue != null && !keyValue.isBlank());
    }

    @Size(max = 1000)
    private String notes;

    @AssertTrue(message = "must be after the created date")
    public boolean isExpiresOnAfterCreatedOn() {
        return expiresOn == null || createdOn == null || expiresOn.isAfter(createdOn);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public void setKeyValue(String keyValue) {
        this.keyValue = keyValue;
    }

    public LocalDate getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(LocalDate createdOn) {
        this.createdOn = createdOn;
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
