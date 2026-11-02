package com.sidbhat.apikeyportal.activity;

import java.util.Locale;

/**
 * What the person did with a key's value when they asked for it.
 * <p>
 * Both are the same act as far as the portal is concerned - the value is
 * decrypted and sent to their browser either way - and both are recorded as a
 * reveal. The distinction is the browser's account of itself, kept because it is
 * useful to whoever reads the history later: a value on the clipboard has
 * probably been pasted somewhere else by now.
 */
public enum RevealMethod {

    /** Shown in the page, by the eye button. */
    SHOWN("Shown on screen"),

    /** Put on the clipboard, by the copy button. */
    COPIED("Copied to the clipboard");

    private final String label;

    RevealMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Reads the hint the browser sent. Anything unrecognised - an old page still
     * open, or somebody calling the endpoint by hand - is taken as a plain reveal
     * rather than refused: the reveal itself is what has to be recorded, and
     * losing the whole event over an unreadable hint would be the wrong trade.
     */
    public static RevealMethod fromParameter(String value) {
        if (value == null) {
            return SHOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return SHOWN;
        }
    }
}
