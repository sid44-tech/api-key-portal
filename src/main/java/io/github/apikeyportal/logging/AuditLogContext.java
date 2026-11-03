package io.github.apikeyportal.logging;

import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.MDC;

/**
 * The who, where and which-request that every log line written while serving a
 * request carries, whoever wrote it.
 * <p>
 * Held in SLF4J's mapped diagnostic context rather than passed around or
 * repeated in each message, so a line logged deep in a service still says who
 * caused it. The log pattern prints these, so "the value of this key was
 * revealed" and "this address was locked out" can be tied to each other and to
 * the request that did it.
 * <p>
 * The MDC is thread-local and a request is served on one thread, so this only
 * holds for work done on the request thread: the scheduled expiry sweep has no
 * request and logs with these fields empty, which is correct - nobody asked for
 * it.
 */
public final class AuditLogContext {

    /** Short id for one request, also returned to the browser as {@code X-Request-Id}. */
    public static final String REQUEST_ID = "requestId";

    /** Email of whoever is signed in, once the request has been authenticated. */
    public static final String USER = "user";

    /** Address the request came from. */
    public static final String CLIENT_IP = "clientIp";

    /** Method and path, so a line is placeable without reading the access log. */
    public static final String REQUEST = "request";

    private AuditLogContext() {
    }

    /** A new id, short enough to quote over the phone and wide enough not to collide in a day. */
    public static String newRequestId() {
        return String.format("%08x", ThreadLocalRandom.current().nextInt());
    }

    public static void put(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    public static String get(String key) {
        return MDC.get(key);
    }

    /**
     * Clears only what this portal put there. {@code MDC.clear()} would also
     * throw away anything the container or another library is keeping, on a
     * thread that is about to be handed back to the pool and reused.
     */
    public static void clear() {
        MDC.remove(REQUEST_ID);
        MDC.remove(USER);
        MDC.remove(CLIENT_IP);
        MDC.remove(REQUEST);
    }
}
