import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for Azure AD that answers the requests the portal makes, so the Azure
 * integration can be set up and used without a real tenant or internet access.
 * <p>
 * Run it straight from source, no build needed:
 * <pre>
 *   java dev/FakeAzure.java                     # listens on port 9090
 *   java dev/FakeAzure.java --port 9091 --delay-seconds 5 --max-secret-days 3
 * </pre>
 * and start the portal with
 * <pre>
 *   PORTAL_AZURE_LOGIN_BASE_URL=http://localhost:9090
 *   PORTAL_AZURE_GRAPH_BASE_URL=http://localhost:9090
 * </pre>
 * Any tenant id, client id and non-empty client secret are accepted. App
 * registrations and their secrets are kept in memory, so stopping it forgets them.
 * <p>
 * Every request is printed as one line, and {@code GET /fake/state} shows every
 * app registration with its live secrets plus running totals of the secrets
 * created and removed. {@code --delay-seconds} holds every response back that
 * long, the way a slow tenant would; keep it under the portal's 30-second
 * timeout for Azure calls. {@code --max-secret-days} caps how long a new secret
 * lasts, the way a tenant's credential lifetime policy does, so a secret can be
 * made to expire within days instead of months.
 */
public class FakeAzure {

    private static final Pattern TOKEN_PATH = Pattern.compile("^/([^/]+)/oauth2/v2\\.0/token$");
    private static final Pattern APPLICATION_PATH = Pattern.compile("^/v1\\.0/applications/([^/]+)$");
    private static final Pattern ADD_PASSWORD_PATH = Pattern.compile("^/v1\\.0/applications/([^/]+)/addPassword$");
    private static final Pattern REMOVE_PASSWORD_PATH = Pattern.compile("^/v1\\.0/applications/([^/]+)/removePassword$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Application> applications = new LinkedHashMap<>();
    private final AtomicInteger tokensIssued = new AtomicInteger();
    private final AtomicInteger secretsCreated = new AtomicInteger();
    private final AtomicInteger secretsRemoved = new AtomicInteger();
    private final long delayMillis;
    private final long maxSecretDays;

    FakeAzure(long delayMillis, long maxSecretDays) {
        this.delayMillis = delayMillis;
        this.maxSecretDays = maxSecretDays;
    }

    public static void main(String[] args) throws IOException {
        int port = 9090;
        long delaySeconds = 0;
        long maxSecretDays = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--delay-seconds" -> delaySeconds = Long.parseLong(args[++i]);
                case "--max-secret-days" -> maxSecretDays = Long.parseLong(args[++i]);
                default -> {
                    System.err.println("Usage: java dev/FakeAzure.java [--port 9090] [--delay-seconds 0]"
                            + " [--max-secret-days N]");
                    System.exit(2);
                }
            }
        }
        FakeAzure azure = new FakeAzure(delaySeconds * 1000, maxSecretDays);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/", azure::handle);
        // one thread per request, so a delayed reply never holds up the next caller
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        log("Fake Azure AD listening on http://localhost:" + port
                + (delaySeconds > 0 ? ", replying after " + delaySeconds + "s" : "")
                + (maxSecretDays > 0 ? ", secrets last at most " + maxSecretDays + " days" : ""));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (method.equals("GET") && path.equals("/fake/state")) {
                send(exchange, 200, state());
                return;
            }
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            Reply reply = route(method, path, body, exchange.getRequestHeaders().getFirst("Authorization"));
            log(method + " " + path + " -> " + reply.status() + (reply.note() == null ? "" : " " + reply.note()));
            send(exchange, reply.status(), reply.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log("Failed to answer " + exchange.getRequestURI() + ": " + e);
            send(exchange, 500, error("InternalServerError", String.valueOf(e.getMessage())));
        }
    }

    private Reply route(String method, String path, String body, String authorization) {
        Matcher token = TOKEN_PATH.matcher(path);
        if (method.equals("POST") && token.matches()) {
            return token(form(body));
        }
        if (!path.startsWith("/v1.0/")) {
            return new Reply(404, error("NotFound", "No such endpoint: " + path), null);
        }
        if (authorization == null || !authorization.startsWith("Bearer fake-token-")) {
            return new Reply(401, error("InvalidAuthenticationToken", "Access token is empty or invalid."), null);
        }
        Matcher match;
        if (method.equals("POST") && path.equals("/v1.0/applications")) {
            return createApplication(body);
        }
        if (method.equals("POST") && path.equals("/v1.0/servicePrincipals")) {
            return new Reply(201, "{\"id\":\"" + UUID.randomUUID() + "\",\"appId\":\""
                    + json(stringField(body, "appId")) + "\"}", null);
        }
        if (method.equals("POST") && (match = ADD_PASSWORD_PATH.matcher(path)).matches()) {
            return addPassword(match.group(1), body);
        }
        if (method.equals("POST") && (match = REMOVE_PASSWORD_PATH.matcher(path)).matches()) {
            return removePassword(match.group(1), stringField(body, "keyId"));
        }
        if (method.equals("GET") && (match = APPLICATION_PATH.matcher(path)).matches()) {
            return readApplication(match.group(1));
        }
        if (method.equals("DELETE") && (match = APPLICATION_PATH.matcher(path)).matches()) {
            return deleteApplication(match.group(1));
        }
        return new Reply(404, error("NotFound", "No such endpoint: " + method + " " + path), null);
    }

    private Reply token(Map<String, String> form) {
        if (form.getOrDefault("client_id", "").isBlank() || form.getOrDefault("client_secret", "").isBlank()) {
            return new Reply(401, "{\"error\":\"invalid_client\",\"error_description\":"
                    + "\"AADSTS7000215: Invalid client secret provided.\"}", null);
        }
        return new Reply(200, "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"fake-token-"
                + tokensIssued.incrementAndGet() + "\"}", null);
    }

    private synchronized Reply createApplication(String body) {
        Application application = new Application(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                stringField(body, "displayName"));
        applications.put(application.id, application);
        return new Reply(201, "{\"id\":\"" + application.id + "\",\"appId\":\"" + application.appId
                + "\",\"displayName\":\"" + json(application.displayName) + "\"}",
                "created app registration " + application.id);
    }

    private synchronized Reply addPassword(String applicationId, String body) {
        Application application = applications.get(applicationId);
        if (application == null) {
            return notFound(applicationId);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String requestedEnd = stringField(body, "endDateTime");
        Instant end = requestedEnd == null ? now.plus(730, ChronoUnit.DAYS) : Instant.parse(requestedEnd);
        if (maxSecretDays > 0 && end.isAfter(now.plus(maxSecretDays, ChronoUnit.DAYS))) {
            // a lifetime policy shortens the secret rather than refusing it; the reply carries the real end
            end = now.plus(maxSecretDays, ChronoUnit.DAYS);
        }
        Secret secret = new Secret(UUID.randomUUID().toString(), stringField(body, "displayName"), now, end);
        application.secrets.add(secret);
        int total = secretsCreated.incrementAndGet();
        return new Reply(200, "{\"keyId\":\"" + secret.keyId + "\",\"displayName\":\"" + json(secret.displayName)
                + "\",\"startDateTime\":\"" + secret.start + "\",\"endDateTime\":\"" + secret.end
                + "\",\"secretText\":\"" + newSecretValue() + "\"}",
                "created secret " + secret.keyId + " on " + applicationId + " (secrets created: " + total + ")");
    }

    private synchronized Reply removePassword(String applicationId, String keyId) {
        Application application = applications.get(applicationId);
        if (application == null) {
            return notFound(applicationId);
        }
        if (!application.secrets.removeIf(secret -> secret.keyId.equals(keyId))) {
            // the wording Graph uses, which the portal treats as "already gone"
            return new Reply(400, error("InvalidKeyId", "No password credential found with keyId " + keyId), null);
        }
        int total = secretsRemoved.incrementAndGet();
        return new Reply(204, "", "removed secret " + keyId + " from " + applicationId
                + " (secrets removed: " + total + ")");
    }

    private synchronized Reply readApplication(String applicationId) {
        Application application = applications.get(applicationId);
        if (application == null) {
            return notFound(applicationId);
        }
        return new Reply(200, application.toJson(), null);
    }

    private synchronized Reply deleteApplication(String applicationId) {
        if (applications.remove(applicationId) == null) {
            return notFound(applicationId);
        }
        return new Reply(204, "", "deleted app registration " + applicationId);
    }

    private synchronized String state() {
        StringBuilder out = new StringBuilder("{\n  \"tokensIssued\": ").append(tokensIssued.get())
                .append(",\n  \"secretsCreated\": ").append(secretsCreated.get())
                .append(",\n  \"secretsRemoved\": ").append(secretsRemoved.get())
                .append(",\n  \"applications\": [");
        String separator = "\n    ";
        for (Application application : applications.values()) {
            out.append(separator).append(application.toJson());
            separator = ",\n    ";
        }
        return out.append(applications.isEmpty() ? "]\n}\n" : "\n  ]\n}\n").toString();
    }

    private static Reply notFound(String applicationId) {
        return new Reply(404, error("Request_ResourceNotFound",
                "Resource '" + applicationId + "' does not exist or one of its queried reference-property objects are not present."), null);
    }

    private static String error(String code, String message) {
        return "{\"error\":{\"code\":\"" + json(code) + "\",\"message\":\"" + json(message) + "\"}}";
    }

    private static String newSecretValue() {
        byte[] bytes = new byte[30];
        RANDOM.nextBytes(bytes);
        return "fake~" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The value of a string field anywhere in a small JSON body; enough for the requests the portal sends. */
    private static String stringField(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(body);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    private static Map<String, String> form(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                fields.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static void log(String line) {
        System.out.println(Instant.now().truncatedTo(ChronoUnit.MILLIS) + " " + line);
    }

    private record Reply(int status, String body, String note) {
    }

    private record Secret(String keyId, String displayName, Instant start, Instant end) {
    }

    private static final class Application {
        final String id;
        final String appId;
        final String displayName;
        final List<Secret> secrets = new ArrayList<>();

        Application(String id, String appId, String displayName) {
            this.id = id;
            this.appId = appId;
            this.displayName = displayName;
        }

        /** Shaped like Graph's application resource: the secrets are listed, their values never are. */
        String toJson() {
            StringBuilder out = new StringBuilder("{\"id\":\"").append(id).append("\",\"appId\":\"").append(appId)
                    .append("\",\"displayName\":\"").append(json(displayName)).append("\",\"passwordCredentials\":[");
            for (int i = 0; i < secrets.size(); i++) {
                Secret secret = secrets.get(i);
                out.append(i == 0 ? "" : ",").append("{\"keyId\":\"").append(secret.keyId)
                        .append("\",\"displayName\":\"").append(json(secret.displayName))
                        .append("\",\"startDateTime\":\"").append(secret.start)
                        .append("\",\"endDateTime\":\"").append(secret.end).append("\"}");
            }
            return out.append("]}").toString();
        }
    }
}
