package io.github.apikeyportal.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The portal's audit trail, as log lines.
 * <p>
 * Everything written here goes to the {@code portal.audit} logger rather than to
 * the class that happened to call it, so an operator can route the audit trail
 * somewhere of its own - a file that is shipped off the box, a different
 * retention, an alert on refusals - without having to know which classes in this
 * application are the interesting ones. The ordinary per-class logging carries
 * on alongside it as the operational narrative; this is the record of who did
 * what.
 * <p>
 * Lines are {@code key=value} so they can be read by a person and parsed by a
 * machine without a format for each kind of event:
 * <pre>
 * event=key_revealed outcome=ok actor=sam@example.com target="Stripe / Prod secret"
 * event=access_denied outcome=refused actor=sam@example.com target=/groups reason="..."
 * </pre>
 * The request id, signed-in user and calling address are not repeated in the
 * message: {@link AuditLogContext} puts them in the logging context, so every
 * line carries them whether it was written here or anywhere else.
 * <p>
 * <b>Nothing secret is ever passed to these methods.</b> Key values, passwords,
 * client secrets and invitation tokens stay out of the audit trail exactly as
 * they stay out of {@code toString()}; what is recorded is that a value was
 * revealed or replaced, never the value itself.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger("portal.audit");

    /**
     * Something happened, and the portal allowed it.
     *
     * @param event  stable name of what happened, e.g. {@code key_revealed}
     * @param actor  who did it, by email, or null where nobody was signed in
     * @param target what they did it to, in human terms
     * @param detail anything else worth keeping, or null
     */
    public void allowed(String event, String actor, String target, String detail) {
        log.info("event={} outcome=ok actor={} target={}{}", event, value(actor), value(target), detail(detail));
    }

    /**
     * Something was attempted and the portal said no. Always a warning: a refusal
     * is either somebody finding the edge of their access or somebody probing it,
     * and both are worth seeing.
     *
     * @param reason why it was refused - never anything the person could use
     */
    public void refused(String event, String actor, String target, String reason) {
        log.warn("event={} outcome=refused actor={} target={} reason={}", event, value(actor), value(target),
                value(reason));
    }

    /**
     * Something the portal does to itself: a scheduled sweep, a configuration
     * change at startup, a key that could not be decrypted.
     */
    public void system(String event, String detail) {
        log.info("event={} outcome=ok actor=system{}", event, detail(detail));
    }

    private static String detail(String detail) {
        return detail == null || detail.isBlank() ? "" : " detail=" + value(detail);
    }

    /**
     * Quotes anything that would otherwise run into the next field, so a name
     * with a space in it cannot make one field look like two.
     */
    static String value(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "-";
        }
        String escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
                // a newline in a log line is how one record is made to look like two
                .replace("\n", "\\n").replace("\r", "\\r");
        return escaped.indexOf(' ') < 0 && escaped.equals(raw) ? escaped : "\"" + escaped + "\"";
    }
}
