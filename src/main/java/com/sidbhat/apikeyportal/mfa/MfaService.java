package com.sidbhat.apikeyportal.mfa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.logging.AuditLog;
import com.sidbhat.apikeyportal.theme.ThemeService;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;

/**
 * Two-factor authentication for password accounts: enrolling, checking codes,
 * recovery codes, and switching it off again.
 * <p>
 * The rules that matter, in one place:
 * <ul>
 *   <li>Nothing is stored until a code from the new authenticator has been
 *       accepted, so an enrolment that was abandoned halfway locks nobody out.</li>
 *   <li>A code is good once. The step it was accepted for is remembered and a
 *       code for that step or an earlier one is refused, however correct.</li>
 *   <li>Recovery codes are kept as hashes and deleted as they are used; the
 *       page that shows them at enrolment is the only time they exist in clear.</li>
 *   <li>Turning it off, or minting new recovery codes, needs the current
 *       password <em>and</em> a code - a stolen session cannot remove the
 *       second factor, and neither can a stolen password alone.</li>
 *   <li>A super admin can reset somebody's second factor for a lost phone,
 *       which is written down against both names.</li>
 * </ul>
 */
@Service
@Transactional
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    public static final int RECOVERY_CODE_COUNT = 10;

    /** Unambiguous: no 0/o, 1/l/i. Ten of these is about 47 bits, read out over a phone without tears. */
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int RECOVERY_CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository accounts;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final PasswordEncoder passwordEncoder;
    private final ActivityService activity;
    private final AuditLog audit;
    private final ThemeService theme;

    public MfaService(UserAccountRepository accounts, MfaRecoveryCodeRepository recoveryCodes,
            PasswordEncoder passwordEncoder, ActivityService activity, AuditLog audit, ThemeService theme) {
        this.accounts = accounts;
        this.recoveryCodes = recoveryCodes;
        this.passwordEncoder = passwordEncoder;
        this.activity = activity;
        this.audit = audit;
        this.theme = theme;
    }

    /**
     * What the enrolment page shows: a fresh secret, as a QR code and as text
     * for typing in. Held in the session by the caller, not in the database -
     * it becomes the account's secret only when {@link #confirmEnrollment} accepts a code.
     */
    public record PendingEnrollment(String secret, String otpauthUri, String qrDataUri) {
    }

    public PendingEnrollment beginEnrollment(UserAccount account) {
        if (account.isSsoLinked()) {
            throw new MfaException("Accounts that sign in through the identity provider use its second factor.");
        }
        return resumeEnrollment(account, Totp.generateSecret());
    }

    /** The same enrolment page again for a secret already shown - after a wrong code, say. */
    public PendingEnrollment resumeEnrollment(UserAccount account, String secret) {
        String uri = Totp.otpauthUri(theme.portalName(), account.getEmail(), secret);
        return new PendingEnrollment(secret, uri, QrCodes.pngDataUri(uri));
    }

    /**
     * Turns two-factor on, once the authenticator has produced a code for the
     * pending secret.
     *
     * @return the recovery codes, in clear, for the one page that shows them
     * @throws MfaException if the code does not match the secret
     */
    public List<String> confirmEnrollment(Long accountId, String pendingSecret, String code) {
        UserAccount account = account(accountId);
        long step = Totp.matchingStep(pendingSecret, code, Totp.currentStep(), -1);
        if (step < 0) {
            audit.refused("mfa_enrolled", account.getEmail(), account.getEmail(), "The code did not match");
            throw new MfaException("That code did not match. Check the time on your phone and try the next one.");
        }
        account.enableMfa(pendingSecret, Instant.now(), step);
        List<String> codes = issueRecoveryCodes(account);
        activity.record(ActivityAction.MFA_CHANGED, account, account.getId(), account.getEmail(), "Switched on");
        audit.allowed("mfa_enrolled", account.getEmail(), account.getEmail(), null);
        log.info("{} switched two-factor authentication on", account.getEmail());
        return codes;
    }

    /**
     * Checks a sign-in code and burns the step it matched.
     *
     * @return true if the code is right, fresh and in the window
     */
    public boolean verifyCode(Long accountId, String code) {
        UserAccount account = account(accountId);
        if (!account.isMfaEnabled()) {
            return false;
        }
        long step = Totp.matchingStep(account.getMfaSecret(), code, Totp.currentStep(), account.getMfaLastUsedStep());
        if (step < 0) {
            audit.refused("mfa_verified", account.getEmail(), account.getEmail(), "Wrong or reused code");
            return false;
        }
        account.recordMfaStep(step);
        audit.allowed("mfa_verified", account.getEmail(), account.getEmail(), "Authenticator code");
        return true;
    }

    /**
     * Checks a recovery code and deletes it. Each works once, by design: a
     * recovery code is a spare key, and a spare key that keeps working after it
     * has been found on the floor is not a spare key.
     */
    public boolean useRecoveryCode(Long accountId, String code) {
        UserAccount account = account(accountId);
        if (!account.isMfaEnabled() || code == null) {
            return false;
        }
        return recoveryCodes.findByUserIdAndCodeHash(account.getId(), hash(normalizeRecoveryCode(code)))
                .map(found -> {
                    recoveryCodes.delete(found);
                    long left = recoveryCodes.countByUserId(account.getId());
                    audit.allowed("mfa_verified", account.getEmail(), account.getEmail(),
                            "Recovery code; " + left + " left");
                    log.warn("{} signed in with a recovery code; {} remain", account.getEmail(), left);
                    return true;
                })
                .orElseGet(() -> {
                    audit.refused("mfa_verified", account.getEmail(), account.getEmail(), "Unknown recovery code");
                    return false;
                });
    }

    public long recoveryCodesRemaining(Long accountId) {
        return recoveryCodes.countByUserId(accountId);
    }

    /**
     * Switches two-factor off. Password and a current code, both: see the class note.
     *
     * @throws MfaException if either does not check out
     */
    public void disable(Long accountId, String currentPassword, String code) {
        UserAccount account = account(accountId);
        requirePasswordAndCode(account, currentPassword, code, "mfa_disabled");
        account.disableMfa();
        recoveryCodes.deleteByUserId(account.getId());
        activity.record(ActivityAction.MFA_CHANGED, account, account.getId(), account.getEmail(), "Switched off");
        audit.allowed("mfa_disabled", account.getEmail(), account.getEmail(), null);
        log.info("{} switched two-factor authentication off", account.getEmail());
    }

    /** Replaces every recovery code, used or not. The same proof as switching off. */
    public List<String> regenerateRecoveryCodes(Long accountId, String currentPassword, String code) {
        UserAccount account = account(accountId);
        requirePasswordAndCode(account, currentPassword, code, "mfa_recovery_codes_regenerated");
        recoveryCodes.deleteByUserId(account.getId());
        List<String> codes = issueRecoveryCodes(account);
        audit.allowed("mfa_recovery_codes_regenerated", account.getEmail(), account.getEmail(), null);
        return codes;
    }

    /**
     * Clears somebody else's second factor - the way back in after a lost phone
     * with no recovery codes. Super admin only, and never for themselves: that
     * would make the break-glass account's second factor one click to remove.
     */
    public void resetByAdmin(Long targetId, UserAccount actor) {
        if (!actor.isSuperAdmin()) {
            throw new AccessDeniedException("Only a super admin can reset two-factor authentication");
        }
        UserAccount target = account(targetId);
        if (actor.is(target)) {
            throw new AccessDeniedException("Switch your own two-factor off from your profile, with your code");
        }
        if (!target.isMfaEnabled()) {
            return;
        }
        target.disableMfa();
        recoveryCodes.deleteByUserId(target.getId());
        activity.record(ActivityAction.MFA_CHANGED, actor, target.getId(), target.getEmail(),
                "Reset by an administrator");
        audit.allowed("mfa_reset", actor.getEmail(), target.getEmail(), "Reset by an administrator");
        log.warn("{} reset two-factor authentication for {}", actor.getEmail(), target.getEmail());
    }

    private void requirePasswordAndCode(UserAccount account, String currentPassword, String code, String event) {
        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword, account.getPasswordHash())) {
            audit.refused(event, account.getEmail(), account.getEmail(), "The current password did not match");
            throw new MfaException("The current password is not right.");
        }
        long step = Totp.matchingStep(account.getMfaSecret(), code, Totp.currentStep(), account.getMfaLastUsedStep());
        if (step >= 0) {
            account.recordMfaStep(step);
            return;
        }
        // a recovery code will do as the second proof too, and is spent like any other
        if (code != null && recoveryCodes.findByUserIdAndCodeHash(account.getId(), hash(normalizeRecoveryCode(code)))
                .map(found -> {
                    recoveryCodes.delete(found);
                    return true;
                }).orElse(false)) {
            return;
        }
        audit.refused(event, account.getEmail(), account.getEmail(), "The code did not match");
        throw new MfaException("That code did not match.");
    }

    private List<String> issueRecoveryCodes(UserAccount account) {
        List<String> codes = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            StringBuilder code = new StringBuilder();
            for (int j = 0; j < RECOVERY_CODE_LENGTH; j++) {
                code.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            recoveryCodes.save(new MfaRecoveryCode(account.getId(), hash(code.toString()), now));
            // shown in two halves, which is how people read ten characters out loud
            codes.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return codes;
    }

    static String normalizeRecoveryCode(String code) {
        return code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private UserAccount account(Long id) {
        return accounts.findById(id).orElseThrow(() -> new IllegalArgumentException("No account " + id));
    }
}
