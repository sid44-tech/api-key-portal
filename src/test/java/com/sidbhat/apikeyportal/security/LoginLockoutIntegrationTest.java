package com.sidbhat.apikeyportal.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * The lockout as somebody at the sign-in page meets it.
 * <p>
 * The limit is set low here, and deliberately not left at the suite-wide one:
 * these properties give this class an application context of its own, so the
 * failures it records on purpose stay out of the counters every other test
 * shares - and out of those tests' way, since they all sign in from the same
 * loopback address.
 */
@SpringBootTest(properties = {
        "portal.security.lockout.max-attempts=3",
        "portal.security.lockout.lockout-for=15m"
})
@AutoConfigureMockMvc
class LoginLockoutIntegrationTest {

    private static final String EMAIL = "locked@example.com";
    private static final String PASSWORD = "a-good-long-password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LoginAttemptService attempts;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserAccount account;

    @BeforeEach
    void createAnAccountToGuessAt() {
        attempts.forgetAll();
        UserGroup group = groups.findAllByOrderByNameAsc().get(0);
        account = accounts.save(new UserAccount(EMAIL, "Locked Out",
                passwordEncoder.encode(PASSWORD), UserRole.MEMBER, group));
    }

    @AfterEach
    void cleanUp() {
        accounts.delete(account);
        attempts.forgetAll();
    }

    private void guessWrong(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mvc.perform(formLogin().user(EMAIL).password("wrong-" + i)).andExpect(unauthenticated());
        }
    }

    @Test
    void wrongPasswordsUnderTheLimitStillJustSayInvalid() throws Exception {
        guessWrong(2);

        mvc.perform(formLogin().user(EMAIL).password("wrong-again"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void theRightPasswordStillWorksUnderTheLimit() throws Exception {
        guessWrong(2);

        mvc.perform(formLogin().user(EMAIL).password(PASSWORD)).andExpect(authenticated());
    }

    @Test
    void enoughWrongPasswordsLockTheAccountOut() throws Exception {
        guessWrong(3);

        mvc.perform(formLogin().user(EMAIL).password("wrong-yet-again"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?lockedOut"));
    }

    @Test
    void theRightPasswordDoesNotGetThroughALockout() throws Exception {
        guessWrong(3);

        // the whole point: the guessing stops working even when a guess is right
        mvc.perform(formLogin().user(EMAIL).password(PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?lockedOut"));
    }

    @Test
    void signingInSuccessfullyClearsTheFailuresBehindIt() throws Exception {
        guessWrong(2);
        mvc.perform(formLogin().user(EMAIL).password(PASSWORD)).andExpect(authenticated());

        // two more would have been the third and fourth of a run, and locked it
        guessWrong(2);

        mvc.perform(formLogin().user(EMAIL).password(PASSWORD)).andExpect(authenticated());
    }

    @Test
    void guessingAtAnAccountThatDoesNotExistIsCountedToo() throws Exception {
        // otherwise working out which addresses are real would be free
        for (int i = 0; i < 3; i++) {
            mvc.perform(formLogin().user("nobody@example.com").password("guess-" + i))
                    .andExpect(unauthenticated());
        }

        mvc.perform(formLogin().user("nobody@example.com").password("guess-again"))
                .andExpect(redirectedUrl("/login?lockedOut"));
    }

    @Test
    void aFloodFromOneCallerLocksThatCallerOutOfEveryAccount() throws Exception {
        // one guess each at three accounts: no single account reaches the limit
        for (int i = 0; i < 3; i++) {
            mvc.perform(formLogin().user("person" + i + "@example.com").password("guess"))
                    .andExpect(unauthenticated());
        }

        // but this caller is now refused, even for an account it has never tried
        mvc.perform(formLogin().user(EMAIL).password(PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?lockedOut"));
    }

    @Test
    void theSignInPageExplainsTheLockoutWithoutSayingWhoseItIs() throws Exception {
        mvc.perform(get("/login?lockedOut"))
                .andExpect(content().string(containsString("Too many failed sign-in attempts")))
                .andExpect(content().string(containsString("15 minutes")))
                // naming the account would turn the message into an oracle for
                // which addresses have accounts here
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(EMAIL))));
    }
}
