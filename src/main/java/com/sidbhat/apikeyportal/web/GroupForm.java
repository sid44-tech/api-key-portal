package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Backing object for adding a group.
 */
public class GroupForm {

    @NotBlank
    @Size(max = 100)
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
