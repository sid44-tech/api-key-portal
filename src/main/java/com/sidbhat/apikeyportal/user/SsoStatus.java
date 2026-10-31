package com.sidbhat.apikeyportal.user;

/**
 * Whether single sign-on is currently switched on.
 * <p>
 * A one-method view of the SSO configuration, declared here so that sign-in can
 * consult it without this package depending on the one that implements it.
 */
public interface SsoStatus {

    boolean isEnabled();
}
