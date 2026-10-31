package com.sidbhat.apikeyportal.activity;

/**
 * How a key came to be in the portal. Recorded against every key creation so the
 * history page can answer "what kind of key was this, and where did it come from"
 * - in particular, which keys were minted through a shared service integration
 * rather than pasted in by one person.
 */
public enum KeySource {

    /** Pasted into the add-key form. */
    MANUAL("Entered by hand"),

    /** Created in a connected provider through a shared service integration. */
    PROVISIONED("Created via integration"),

    /** Created in the provider's own console, then verified and stored. */
    GUIDED("Guided walk-through");

    private final String label;

    KeySource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
