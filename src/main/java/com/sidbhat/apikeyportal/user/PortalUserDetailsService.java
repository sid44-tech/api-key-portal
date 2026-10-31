package com.sidbhat.apikeyportal.user;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Looks password sign-ins up against the {@code user_account} table. The portal
 * used to have a single set of credentials in configuration; those now seed the
 * first super admin instead (see {@code BootstrapAdminInitializer}).
 * <p>
 * Accounts linked to the identity provider are refused here while single sign-on
 * is on, so the directory group stays the only way in for them.
 */
@Service
@Transactional(readOnly = true)
public class PortalUserDetailsService implements UserDetailsService {

    private final UserAccountRepository repository;

    /** Optional so a sliced test can stand this up without the SSO machinery. */
    private final ObjectProvider<SsoStatus> ssoStatus;

    public PortalUserDetailsService(UserAccountRepository repository, ObjectProvider<SsoStatus> ssoStatus) {
        this.repository = repository;
        this.ssoStatus = ssoStatus;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserAccount account = repository.findByEmailIgnoreCase(UserAccount.normalizeEmail(email))
                // the message never reaches the user; the login page says "invalid username or password"
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));
        if (account.isSsoLinked() && ssoEnabled()) {
            throw new SsoSignInRequiredException("This account signs in through the identity provider");
        }
        return new PortalUser(account);
    }

    /**
     * Switching single sign-on off hands these accounts their passwords back,
     * which is what keeps a broken provider from locking everybody out.
     */
    private boolean ssoEnabled() {
        SsoStatus status = ssoStatus.getIfAvailable();
        return status != null && status.isEnabled();
    }
}
