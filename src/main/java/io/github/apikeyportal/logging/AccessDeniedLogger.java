package io.github.apikeyportal.logging;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.stereotype.Component;

/**
 * Writes down every refusal, then hands over to the default handler to produce
 * the 403 the user sees.
 * <p>
 * One place rather than twenty: the portal refuses things from all over - a key
 * in another group, a role only a super admin can grant, somebody else's
 * invitation, the history page for a member - and each of those is a
 * {@link AccessDeniedException} thrown by the service that knows why. They all
 * end up here on their way out, so the refusal is recorded once, consistently,
 * with the user and address already in the logging context.
 * <p>
 * This is the signal that matters most on a portal full of secrets. One refusal
 * is somebody clicking a stale link. A run of them from one account is somebody
 * finding out what they can reach.
 */
@Component
public class AccessDeniedLogger implements AccessDeniedHandler {

    private final AccessDeniedHandler delegate = new AccessDeniedHandlerImpl();

    private final AuditLog audit;

    public AccessDeniedLogger(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException, ServletException {
        // The message is the portal's own explanation of the rule that was hit
        // ("Key 7 belongs to another group"), written for this log as much as
        // for the exception. It never contains anything the caller gave us.
        audit.refused("access_denied", AuditLogContext.get(AuditLogContext.USER),
                request.getRequestURI(), exception.getMessage());
        delegate.handle(request, response, exception);
    }
}
