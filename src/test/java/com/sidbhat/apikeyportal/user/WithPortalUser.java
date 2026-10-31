package com.sidbhat.apikeyportal.user;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * Signs the test in as one of the {@link TestUsers} fixtures, with a real
 * {@link PortalUser} principal - which is what the controllers resolve.
 * {@code @WithMockUser} can't be used here, as it provides a plain user with no
 * account id, role or group behind it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@WithSecurityContext(factory = WithPortalUserSecurityContextFactory.class)
public @interface WithPortalUser {

    /** One of the {@link TestUsers} fixture names: alex, sam, outsider or superadmin. */
    String value() default "alex";
}
