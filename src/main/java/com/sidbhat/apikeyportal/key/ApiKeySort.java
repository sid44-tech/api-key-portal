package com.sidbhat.apikeyportal.key;

/**
 * Sort options offered on the key list.
 */
public enum ApiKeySort {

    SERVICE("Service"),
    EXPIRES("Expires soonest"),
    CREATED("Newest first");

    private final String label;

    ApiKeySort(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
