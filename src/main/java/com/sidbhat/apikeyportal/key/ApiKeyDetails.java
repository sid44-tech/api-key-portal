package com.sidbhat.apikeyportal.key;

import java.time.LocalDate;

/**
 * The user-editable fields of a key, as passed to {@link ApiKeyService} for create and update.
 * Values are passed through as entered; the service handles trimming and defaults.
 */
public final class ApiKeyDetails {

    private final String name;
    private final String serviceName;
    private final String keyValue;
    private final LocalDate createdOn;
    private final LocalDate expiresOn;
    private final String notes;

    public ApiKeyDetails(String name, String serviceName, String keyValue, LocalDate createdOn,
            LocalDate expiresOn, String notes) {
        this.name = name;
        this.serviceName = serviceName;
        this.keyValue = keyValue;
        this.createdOn = createdOn;
        this.expiresOn = expiresOn;
        this.notes = notes;
    }

    public String getName() {
        return name;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public LocalDate getCreatedOn() {
        return createdOn;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public String getNotes() {
        return notes;
    }

    @Override
    public String toString() {
        return "ApiKeyDetails{name='" + name + "', serviceName='" + serviceName + "'}";
    }
}
