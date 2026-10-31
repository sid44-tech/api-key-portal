package com.sidbhat.apikeyportal.invite;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends the invitation link.
 * <p>
 * The mail sender is optional: Spring Boot only creates one when {@code spring.mail.host}
 * is set. Without it the portal still issues invitations and logs the link, so a
 * local install works before any SMTP server does - the page hands the link to
 * the administrator to pass on themselves.
 */
@Component
public class InvitationMailer {

    private static final Logger log = LoggerFactory.getLogger(InvitationMailer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final InvitationProperties properties;

    public InvitationMailer(ObjectProvider<JavaMailSender> mailSender, InvitationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * @return true if an email actually went out; false if mail isn't configured
     *         or the server rejected it, in which case the link is the caller's
     *         to deliver
     */
    public boolean send(UserInvitation invitation, String link) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            // the link itself stays out of the log: possession of that token is the whole
            // of the authorisation to create the account, and logs travel further than
            // the database this deliberately keeps it out of
            log.info("No mail server configured; the invitation for {} must be passed on by hand",
                    invitation.getEmail());
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(invitation.getEmail());
        message.setSubject("You've been invited to the API Key Portal");
        message.setText(body(invitation, link));
        try {
            sender.send(message);
            log.info("Emailed an invitation to {}", invitation.getEmail());
            return true;
        } catch (MailException e) {
            // the invitation is already saved and still valid, so this is recoverable:
            // the admin can hand over the link or resend once the mail server is fixed
            log.error("Could not email the invitation to {}", invitation.getEmail(), e);
            return false;
        }
    }

    private String body(UserInvitation invitation, String link) {
        String inviter = invitation.getInvitedBy() == null ? "An administrator"
                : invitation.getInvitedBy().getDisplayName();
        String group = invitation.getGroup() == null ? "the portal" : invitation.getGroup().getName();
        return """
                %s has invited you to join %s on the API Key Portal.

                Set up your account here:
                %s

                The link is good for %s and can only be used once. If it expires, ask
                %s to send you a new one.

                If you weren't expecting this, you can ignore this email - nothing is
                created until the link is used.
                """.formatted(inviter, group, link, humanize(properties.getValidFor()), inviter);
    }

    private static String humanize(Duration duration) {
        long hours = duration.toHours();
        if (hours == 24) {
            return "24 hours";
        }
        return hours > 0 ? hours + " hours" : duration.toMinutes() + " minutes";
    }
}
