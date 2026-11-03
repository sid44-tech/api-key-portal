package io.github.apikeyportal.logging;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request id and calling address into the logging context for the life
 * of a request, and takes them out again afterwards.
 * <p>
 * Outermost on purpose - ahead of Spring Security, not inside it - so that a
 * request refused before it ever reaches a controller still logs where it came
 * from. A failed sign-in, a locked-out caller and a CSRF rejection are all
 * written from inside the security chain, and all of them are worth an address.
 * <p>
 * The signed-in user is not known this early; {@link PrincipalLogContextFilter}
 * adds it once the request has been authenticated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditContextFilter extends OncePerRequestFilter {

    /** Returned on every response so somebody reporting a problem can quote it. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = AuditLogContext.newRequestId();
        try {
            AuditLogContext.put(AuditLogContext.REQUEST_ID, requestId);
            AuditLogContext.put(AuditLogContext.CLIENT_IP, request.getRemoteAddr());
            // the path only - a query string can carry things worth not writing down
            AuditLogContext.put(AuditLogContext.REQUEST, request.getMethod() + " " + request.getRequestURI());
            response.setHeader(REQUEST_ID_HEADER, requestId);
            chain.doFilter(request, response);
        } finally {
            // the thread goes back to the pool to serve somebody else; anything
            // left here would be logged against them
            AuditLogContext.clear();
        }
    }

    /**
     * Static resources are served by the hundred and say nothing about who did
     * what. Skipping them keeps the audit trail readable.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/webjars/")
                || path.equals("/favicon.ico");
    }
}
