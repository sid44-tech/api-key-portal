package com.sidbhat.apikeyportal.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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

    /** A sign-in attempt arriving from {@code address} rather than MockMvc's loopback. */
    private static RequestPostProcessor fromAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
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
    void enoughWrongPasswordsLockThatCallerOut() throws Exception {
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
    void aFloodFromOneAddressNeverLocksTheOwnerOutOfTheirOwnAccount() throws Exception {
        // anyone can type anyone's email here, so this used to be a free denial
        // of service on any account - the super admin's included, which is
        // supposed to be the emergency way in when SSO is broken
        guessWrong(3);

        // the prankster's address is locked; the owner, from their own machine,
        // sails straight in
        mvc.perform(post("/login").param("username", EMAIL).param("password", PASSWORD)
                        .with(csrf()).with(fromAddress("203.0.113.9")))
                .andExpect(authenticated());
    }

    @Test
    void guessesSpreadAcrossEnoughAddressesLockTheAccountForEveryone() throws Exception {
        // an address per guess is how a distributed run stays under the
        // per-address limit; that spread is exactly what the account lock is for
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/login").param("username", EMAIL).param("password", "guess-" + i)
                            .with(csrf()).with(fromAddress("198.51.100." + i)))
                    .andExpect(unauthenticated());
        }

        // now even the right password from a fresh address is refused
        mvc.perform(post("/login").param("username", EMAIL).param("password", PASSWORD)
                        .with(csrf()).with(fromAddress("203.0.113.9")))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?lockedOut"));
    }

    @Test
    void signingInSuccessfullyClearsTheFailuresAgainstTheAccount() throws Exception {
        guessWrong(2);
        mvc.perform(formLogin().user(EMAIL).password(PASSWORD)).andExpect(authenticated());

        // two more, from two more addresses: as the third and fourth of one run
        // across three addresses they would have locked the account
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/login").param("username", EMAIL).param("password", "wrong-again-" + i)
                            .with(csrf()).with(fromAddress("198.51.100." + i)))
                    .andExpect(unauthenticated());
        }

        mvc.perform(post("/login").param("username", EMAIL).param("password", PASSWORD)
                        .with(csrf()).with(fromAddress("203.0.113.9")))
                .andExpect(authenticated());
    }

    @Test
    void signingIntoYourOwnAccountDoesNotBuyMoreGuessesAtOthers() throws Exception {
        // an attacker with a valid account of their own - here, the suite's admin
        guessWrong(2);
        mvc.perform(formLogin().user("admin@example.com").password("test-password"))
                .andExpect(authenticated());

        // one address, one more guess at somebody else: the address's third
        // failure, and the sign-in in between is no help at all
        mvc.perform(formLogin().user(EMAIL).password("wrong-after-signing-in")).andExpect(unauthenticated());
        mvc.perform(formLogin().user(EMAIL).password(PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?lockedOut"));
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
