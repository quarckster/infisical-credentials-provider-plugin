package io.jenkins.plugins.infisical;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * In-process stand-in for Infisical's two endpoints, backed by the JDK HttpServer
 * (no external mock dependency). Configurable enough to drive the client tests:
 * good/bad credentials, token reuse vs. expiry, a one-shot 401 on listing, and 403.
 */
final class MockInfisicalServer implements AutoCloseable {

    /** One secret as the mock will emit it. */
    record Secret(String key, String value, Map<String, String> metadata) {
        Secret(String key, String value) {
            this(key, value, Map.of());
        }
    }

    private final HttpServer server;
    private final List<Secret> secrets = new ArrayList<>();

    private volatile String clientId = "client-id";
    private volatile String clientSecret = "client-secret";
    private volatile long expiresIn = 3600; // seconds; large => client reuses the token
    private volatile boolean failNextListWith401 = false;
    private volatile boolean listReturns403 = false;

    private volatile String issuedToken;
    private volatile String lastListQuery;
    private final AtomicInteger loginCount = new AtomicInteger();
    private final AtomicInteger listCount = new AtomicInteger();

    MockInfisicalServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/auth/universal-auth/login", this::handleLogin);
        server.createContext("/api/v3/secrets/raw", this::handleList);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    MockInfisicalServer credentials(String id, String secret) {
        this.clientId = id;
        this.clientSecret = secret;
        return this;
    }

    MockInfisicalServer expiresIn(long seconds) {
        this.expiresIn = seconds;
        return this;
    }

    MockInfisicalServer addSecret(String key, String value) {
        secrets.add(new Secret(key, value));
        return this;
    }

    MockInfisicalServer addSecret(String key, String value, Map<String, String> metadata) {
        secrets.add(new Secret(key, value, metadata));
        return this;
    }

    void failNextListWith401() {
        this.failNextListWith401 = true;
    }

    void listReturns403(boolean v) {
        this.listReturns403 = v;
    }

    int loginCount() {
        return loginCount.get();
    }

    int listCount() {
        return listCount.get();
    }

    String lastListQuery() {
        return lastListQuery;
    }

    private void handleLogin(HttpExchange ex) throws IOException {
        try {
            loginCount.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            if (!clientId.equals(form.get("clientId")) || !clientSecret.equals(form.get("clientSecret"))) {
                respond(ex, 401, "{\"message\":\"invalid credentials\"}");
                return;
            }
            issuedToken = "token-" + loginCount.get();
            JSONObject json = new JSONObject();
            json.put("accessToken", issuedToken);
            json.put("expiresIn", expiresIn);
            json.put("tokenType", "Bearer");
            respond(ex, 200, json.toString());
        } finally {
            ex.close();
        }
    }

    private void handleList(HttpExchange ex) throws IOException {
        try {
            listCount.incrementAndGet();
            lastListQuery = ex.getRequestURI().getRawQuery();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            boolean tokenOk = auth != null && issuedToken != null && auth.equals("Bearer " + issuedToken);
            if (failNextListWith401) {
                failNextListWith401 = false;
                respond(ex, 401, "{\"message\":\"token expired\"}");
                return;
            }
            if (!tokenOk) {
                respond(ex, 401, "{\"message\":\"unauthorized\"}");
                return;
            }
            if (listReturns403) {
                respond(ex, 403, "{\"message\":\"forbidden\"}");
                return;
            }
            JSONArray arr = new JSONArray();
            for (Secret s : secrets) {
                JSONObject o = new JSONObject();
                o.put("secretKey", s.key());
                o.put("secretValue", s.value());
                if (!s.metadata().isEmpty()) {
                    JSONArray md = new JSONArray();
                    for (Map.Entry<String, String> e : s.metadata().entrySet()) {
                        JSONObject kv = new JSONObject();
                        kv.put("key", e.getKey());
                        kv.put("value", e.getValue());
                        md.add(kv);
                    }
                    o.put("secretMetadata", md);
                }
                arr.add(o);
            }
            JSONObject root = new JSONObject();
            root.put("secrets", arr);
            root.put("imports", new JSONArray());
            respond(ex, 200, root.toString());
        } finally {
            ex.close();
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
