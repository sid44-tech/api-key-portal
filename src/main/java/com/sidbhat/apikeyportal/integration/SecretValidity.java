package com.sidbhat.apikeyportal.integration;

/**
 * How long a provisioned client secret stays valid. Azure caps client secrets at
 * two years; shorter is better practice.
 */
public enum SecretValidity {

    DAYS_90("90 days", 90),
    DAYS_180("180 days (recommended)", 180),
    YEAR_1("1 year", 365),
    YEARS_2("2 years", 730);

    private final String label;
    private final int days;

    SecretValidity(String label, int days) {
        this.label = label;
        this.days = days;
    }

    public String getLabel() {
        return label;
    }

    public int getDays() {
        return days;
    }
}
