package io.github.apikeyportal.web;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import io.github.apikeyportal.activity.RevealMethod;
import io.github.apikeyportal.key.ApiKeyService;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccountService;

/**
 * Returns the decrypted value of a single key. Values are only ever sent to the
 * browser on demand through this endpoint, never rendered into pages, and only
 * to the person who owns the key - group members see the metadata and nothing more.
 */
@RestController
public class ApiKeyValueController {

    private final ApiKeyService apiKeyService;
    private final UserAccountService userService;
    private final ObjectMapper objectMapper;

    public ApiKeyValueController(ApiKeyService apiKeyService, UserAccountService userService,
            ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes the value straight to the response rather than returning it.
     * <p>
     * This looks like the long way round, and it is there for one reason: a
     * returned {@code @ResponseBody} is handed to Spring's message converters,
     * and they log what they are about to write at DEBUG -
     * {@code Writing [{value=sk_live_...}]}. Turning up {@code
     * org.springframework.web} is the first thing anybody does when a request
     * misbehaves, and on this one endpoint it would put every revealed key
     * value in the log in clear. Writing the body here means no framework
     * component ever holds it as something loggable.
     * <p>
     * The log level is also pinned in {@code application.yml} as a second lock,
     * but that one can be overridden by configuration and this cannot.
     * <p>
     * A POST, not a GET, although it changes nothing the caller can see. A
     * reveal is recorded in the history and on the key, and a GET could be
     * triggered by any page the owner happened to have open - an image tag on
     * another site, say. The other site could never read the value, but it could
     * fill the owner's history with reveals they never made, which is the one
     * record an audit relies on. As a POST it needs the CSRF token, which only
     * this site's own pages have.
     *
     * @param intent what the browser is about to do with the value, for the
     *               history. A hint, not a permission - showing and copying are
     *               the same call, and both are recorded as a reveal either way.
     */
    @PostMapping("/keys/{id}/value")
    public void value(@PathVariable Long id,
            @RequestParam(name = "intent", required = false) String intent,
            @AuthenticationPrincipal PortalUser principal,
            HttpServletResponse response) throws IOException {
        String value = apiKeyService.revealValue(id, userService.getCurrent(principal),
                RevealMethod.fromParameter(intent));

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // never cached: a revealed value must not sit in a shared cache or in
        // the back button
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        // Jackson for the escaping, so a key value containing a quote or a
        // backslash cannot break the JSON it is being written into
        objectMapper.writeValue(response.getOutputStream(), Map.of("value", value));
    }
}
