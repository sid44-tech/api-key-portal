package com.sidbhat.apikeyportal.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sidbhat.apikeyportal.logging.AuditLog;
import com.sidbhat.apikeyportal.mfa.MfaException;
import com.sidbhat.apikeyportal.mfa.MfaProperties;
import com.sidbhat.apikeyportal.mfa.MfaService;
import com.sidbhat.apikeyportal.mfa.MfaService.PendingEnrollment;
import com.sidbhat.apikeyportal.security.LoginAttemptService;
import com.sidbhat.apikeyportal.security.LoginAuditListener;
import com.sidbhat.apikeyportal.security.MfaAuthenticationSuccessHandler;
import com.sidbhat.apikeyportal.security.PrincipalRefresher;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * Two-factor authentication: the code page at sign-in, and enrolling,
 * managing and switching it off from the profile.
 */
@Controller
@RequestMapping("/mfa")
public class MfaController {

    /** Session attribute: the secret being enrolled, until a code confirms it. */
    public static final String PENDING_SECRET_ATTRIBUTE = "MFA_PENDING_SECRET";

    /**
     * Wrong codes before the sign-in is abandoned. Each also counts towards the
     * ordinary lockout, so a flood of guesses locks the address as a flood of
     * passwords would; this cap just ends the session sooner.
     */
    static final int MAX_FAILURES_PER_SIGN_IN = 5;

    private final MfaService mfa;
    private final UserAccountService userService;
    private final LoginAttemptService attempts;
    private final LoginAuditListener loginAudit;
    private final PrincipalRefresher principalRefresher;
    private final MfaProperties properties;
    private final AuditLog audit;

    public MfaController(MfaService mfa, UserAccountService userService, LoginAttemptService attempts,
            LoginAuditListener loginAudit, PrincipalRefresher principalRefresher, MfaProperties properties,
            AuditLog audit) {
        this.mfa = mfa;
        this.userService = userService;
        this.attempts = attempts;
        this.loginAudit = loginAudit;
        this.principalRefresher = principalRefresher;
        this.properties = properties;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- signing in

    @GetMapping("/verify")
    public String verifyForm(@RequestParam(value = "recovery", required = false) boolean recovery,
            HttpServletRequest request, Model model) {
        if (!MfaAuthenticationSuccessHandler.isPending(request)) {
            return "redirect:/keys";
        }
        model.addAttribute("recovery", recovery);
        return "mfa/verify";
    }

    @PostMapping("/verify")
    public String verify(@RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "recoveryCode", required = false) String recoveryCode,
            @AuthenticationPrincipal PortalUser principal, HttpServletRequest request,
            HttpServletResponse response, Model model) {
        if (!MfaAuthenticationSuccessHandler.isPending(request)) {
            return "redirect:/keys";
        }
        String address = request.getRemoteAddr();
        if (attempts.isLockedOut(principal.getEmail(), address)) {
            endSignIn(request, "Locked out while a code was owed");
            return "redirect:/login?lockedOut";
        }
        boolean recovery = recoveryCode != null && !recoveryCode.isBlank();
        boolean accepted = recovery ? mfa.useRecoveryCode(principal.getId(), recoveryCode)
                : mfa.verifyCode(principal.getId(), code);
        if (accepted) {
            HttpSession session = request.getSession();
            session.removeAttribute(MfaAuthenticationSuccessHandler.PENDING_ATTRIBUTE);
            session.removeAttribute(MfaAuthenticationSuccessHandler.FAILURES_ATTRIBUTE);
            // a fresh id for the fully signed-in session, as there was for the half one
            request.changeSessionId();
            loginAudit.onMfaCompleted(principal.getEmail(), address, recovery ? "recovery code" : "authenticator");
            if (recovery) {
                long left = mfa.recoveryCodesRemaining(principal.getId());
                request.getSession().setAttribute("MFA_RECOVERY_NOTICE", left);
            }
            return "redirect:" + landingUrl(request, response);
        }
        // a wrong code counts as a wrong password would, towards the lockout
        attempts.recordFailure(principal.getEmail(), address);
        int failures = failures(request) + 1;
        request.getSession().setAttribute(MfaAuthenticationSuccessHandler.FAILURES_ATTRIBUTE, failures);
        if (failures >= MAX_FAILURES_PER_SIGN_IN) {
            endSignIn(request, failures + " wrong codes");
            return "redirect:/login?mfaFailed";
        }
        model.addAttribute("recovery", recovery);
        model.addAttribute("error", recovery ? "That recovery code is not one of yours, or has been used."
                : "That code did not match. Codes change every 30 seconds; try the current one.");
        model.addAttribute("attemptsLeft", MAX_FAILURES_PER_SIGN_IN - failures);
        return "mfa/verify";
    }

    private static int failures(HttpServletRequest request) {
        Object value = request.getSession().getAttribute(MfaAuthenticationSuccessHandler.FAILURES_ATTRIBUTE);
        return value instanceof Integer count ? count : 0;
    }

    /** Where the person was going before the sign-in page got in the way, or the key list. */
    private static String landingUrl(HttpServletRequest request, HttpServletResponse response) {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        SavedRequest saved = cache.getRequest(request, response);
        if (saved != null) {
            cache.removeRequest(request, response);
            return saved.getRedirectUrl();
        }
        return "/keys";
    }

    /** The half sign-in is over: nothing of it survives. */
    private void endSignIn(HttpServletRequest request, String why) {
        String email = SecurityContextHolder.getContext().getAuthentication() == null ? "?"
                : SecurityContextHolder.getContext().getAuthentication().getName();
        audit.refused("sign_in", email, email, "Second factor not completed: " + why);
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    // ---------------------------------------------------------------- the profile side

    /** Status and the ways to change it. */
    @GetMapping
    public String manage(@AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        model.addAttribute("account", me);
        model.addAttribute("required", properties.required());
        if (me.isMfaEnabled()) {
            model.addAttribute("recoveryCodesLeft", mfa.recoveryCodesRemaining(me.getId()));
        }
        return "mfa/manage";
    }

    @GetMapping("/setup")
    public String setup(@AuthenticationPrincipal PortalUser principal, HttpSession session, Model model) {
        UserAccount me = userService.getCurrent(principal);
        if (me.isMfaEnabled()) {
            return "redirect:/mfa";
        }
        PendingEnrollment pending;
        try {
            pending = mfa.beginEnrollment(me);
        } catch (MfaException e) {
            return "redirect:/profile";
        }
        session.setAttribute(PENDING_SECRET_ATTRIBUTE, pending.secret());
        model.addAttribute("pending", pending);
        model.addAttribute("required", properties.required());
        return "mfa/setup";
    }

    @PostMapping("/setup")
    public String confirmSetup(@RequestParam("code") String code, @AuthenticationPrincipal PortalUser principal,
            HttpServletRequest request, HttpServletResponse response, HttpSession session, Model model,
            RedirectAttributes redirectAttributes) {
        UserAccount me = userService.getCurrent(principal);
        Object secret = session.getAttribute(PENDING_SECRET_ATTRIBUTE);
        if (!(secret instanceof String pendingSecret)) {
            // the page was reached without starting an enrolment; start one
            return "redirect:/mfa/setup";
        }
        try {
            List<String> codes = mfa.confirmEnrollment(me.getId(), pendingSecret, code);
            session.removeAttribute(PENDING_SECRET_ATTRIBUTE);
            // the principal is a snapshot; the filters must see the second factor as on
            principalRefresher.refresh(userService.getById(me.getId()), request, response);
            redirectAttributes.addFlashAttribute("recoveryCodes", codes);
            redirectAttributes.addFlashAttribute("justEnrolled", true);
            return "redirect:/mfa/recovery-codes";
        } catch (MfaException e) {
            // same secret, same QR code: the phone already has it
            model.addAttribute("pending", mfa.resumeEnrollment(me, pendingSecret));
            model.addAttribute("required", properties.required());
            model.addAttribute("error", e.getMessage());
            return "mfa/setup";
        }
    }

    /** Shown exactly once, from a flash attribute; a reload shows nothing. */
    @GetMapping("/recovery-codes")
    public String recoveryCodes(Model model) {
        if (!model.containsAttribute("recoveryCodes")) {
            return "redirect:/mfa";
        }
        return "mfa/recovery-codes";
    }

    @PostMapping("/recovery-codes")
    public String regenerateRecoveryCodes(@RequestParam("currentPassword") String currentPassword,
            @RequestParam("code") String code, @AuthenticationPrincipal PortalUser principal,
            RedirectAttributes redirectAttributes) {
        try {
            List<String> codes = mfa.regenerateRecoveryCodes(principal.getId(), currentPassword, code);
            redirectAttributes.addFlashAttribute("recoveryCodes", codes);
            return "redirect:/mfa/recovery-codes";
        } catch (MfaException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/mfa";
        }
    }

    @PostMapping("/disable")
    public String disable(@RequestParam("currentPassword") String currentPassword, @RequestParam("code") String code,
            @AuthenticationPrincipal PortalUser principal, HttpServletRequest request, HttpServletResponse response,
            RedirectAttributes redirectAttributes) {
        try {
            mfa.disable(principal.getId(), currentPassword, code);
            principalRefresher.refresh(userService.getById(principal.getId()), request, response);
            redirectAttributes.addFlashAttribute("success", "Two-factor authentication is off. Your password alone signs you in again.");
            return "redirect:/profile";
        } catch (MfaException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/mfa";
        }
    }
}
