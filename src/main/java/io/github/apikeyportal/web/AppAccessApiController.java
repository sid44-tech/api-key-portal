package io.github.apikeyportal.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import io.github.apikeyportal.appaccess.AppAccessException;
import io.github.apikeyportal.appaccess.AppAccessService;
import io.github.apikeyportal.appaccess.AppKeyRead;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The read API for applications. Authenticated by an app token in the
 * {@code Authorization: Bearer} header and nothing else - no session, no
 * cookies - which is why plain GETs are safe here: a browser never sends the
 * token on its own, so another site cannot make a request with it.
 * <p>
 * Every response is written straight to the servlet response, for the same
 * reason as {@link ApiKeyValueController}: no framework component ever holds a
 * key value as something it might log.
 */
@RestController
@RequestMapping("/api/v1")
public class AppAccessApiController {

    private static final String BEARER = "Bearer ";

    private final AppAccessService appAccess;
    private final ObjectMapper objectMapper;

    public AppAccessApiController(AppAccessService appAccess, ObjectMapper objectMapper) {
        this.appAccess = appAccess;
        this.objectMapper = objectMapper;
    }

    /** The keys this token can read: references, names and expiry dates, never values. */
    @GetMapping("/keys")
    public void list(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<AppKeyRead> keys = appAccess.listKeys(bearerToken(request), client(request));
        write(response, HttpServletResponse.SC_OK,
                Map.of("keys", keys.stream().map(AppAccessApiController::describe).toList()));
    }

    /** The current value behind one reference; after a rotation, the replacement's. */
    @GetMapping("/keys/{ref}")
    public void read(@PathVariable("ref") long ref, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AppKeyRead key = appAccess.readKey(bearerToken(request), ref, client(request));
        Map<String, Object> body = describe(key);
        body.put("value", key.value());
        write(response, HttpServletResponse.SC_OK, body);
    }

    @ExceptionHandler(AppAccessException.class)
    public void refused(AppAccessException e, HttpServletResponse response) throws IOException {
        if (e.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"api-key-portal\"");
        }
        write(response, e.getStatus(), Map.of("error", e.getCode(), "message", e.getMessage()));
    }

    private static Map<String, Object> describe(AppKeyRead key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", key.ref());
        body.put("keyId", key.keyId());
        body.put("name", key.name());
        body.put("service", key.service());
        body.put("expiresOn", key.expiresOn() == null ? null : key.expiresOn().toString());
        return body;
    }

    private void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : null;
    }

    private static String client(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
