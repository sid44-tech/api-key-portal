package com.sidbhat.apikeyportal.user;

/**
 * The parts of an account its owner can change from the profile page.
 */
public record ProfileDetails(String email, String displayName, String jobTitle) {
}
