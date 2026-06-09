package io.jenkins.plugins.infisical;

import org.jspecify.annotations.NonNull;
import hudson.util.Secret;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * Minimal client for the two Infisical endpoints this plugin uses: Universal Auth
 * login and the raw-secrets listing. Holds a short-lived access token, refreshes
 * it when expired (with a safety margin), re-logs in once on a 401, and maps HTTP
 * failures onto {@link InfisicalException.Kind} so callers get actionable errors.
 *
 * <p>One instance is built per resolved configuration (see
 * {@link InfisicalGlobalConfiguration#createClient()}); token access is guarded so
 * concurrent callers do not stampede the login endpoint.
 */
public class InfisicalClient {

    /** Refresh the token this many seconds before it actually expires. */
    private static final long EXPIRY_SAFETY_MARGIN_SECONDS = 60;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final String baseUrl;
    private final String projectId;
    private final String projectParamName;
    private final String environment;
    private final String secretPath;
    private final String clientId;
    private final Secret clientSecret;
    private final HttpClient http;

    private final Object tokenLock = new Object();
    private String accessToken;
    private long tokenExpiresAtEpochMs;

    public InfisicalClient(
            @NonNull String serverUrl,
            @NonNull String projectId,
            @NonNull String environment,
            @NonNull String secretPath,
            @NonNull String clientId,
            @NonNull Secret clientSecret) {
        this.baseUrl = normalizeBaseUrl(serverUrl);
        this.projectId = projectId;
        // The raw-secrets endpoint accepts the project either as a UUID (workspaceId)
        // or as a slug (workspaceSlug). Detect which, so configuring the project by
        // slug works as-is on Infisical instances that expect a slug.
        this.projectParamName = isUuid(projectId) ? "workspaceId" : "workspaceSlug";
        this.environment = environment;
        this.secretPath = (secretPath == null || secretPath.isBlank()) ? "/" : secretPath;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Normalize a user-entered server URL to a base we can append {@code /api/...}
     * to: trim, drop trailing slashes, and strip a trailing {@code /api} segment so
     * that URLs entered as {@code https://host} and {@code https://host/api/} behave
     * identically.
     */
    @NonNull
    static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Infisical server URL is required");
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/api")) {
            u = u.substring(0, u.length() - "/api".length());
        }
        return u;
    }

    /** True if the project identifier is a UUID (→ workspaceId), false if a slug (→ workspaceSlug). */
    static boolean isUuid(String s) {
        return s != null && UUID_PATTERN.matcher(s.trim()).matches();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Force a fresh login. Caller holds {@link #tokenLock}. */
    private void login() throws InfisicalException {
        String body = "clientId=" + enc(clientId) + "&clientSecret=" + enc(Secret.toString(clientSecret));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/universal-auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = send(req, "Universal Auth login");
        int status = resp.statusCode();
        if (status == 401 || status == 403) {
            throw new InfisicalException(InfisicalException.Kind.AUTHENTICATION,
                    "Infisical rejected the machine-identity credentials (HTTP " + status
                            + "). Check the clientId/clientSecret.");
        }
        if (status / 100 != 2) {
            throw new InfisicalException(InfisicalException.Kind.PROTOCOL,
                    "Unexpected HTTP " + status + " from Universal Auth login: " + snippet(resp.body()));
        }
        try {
            JSONObject root = JSONObject.fromObject(resp.body());
            String token = root.optString("accessToken", null);
            if (token == null || token.isEmpty()) {
                throw new InfisicalException(InfisicalException.Kind.PROTOCOL,
                        "Login succeeded but no accessToken was returned");
            }
            long expiresIn = root.optLong("expiresIn", 0);
            this.accessToken = token;
            this.tokenExpiresAtEpochMs = expiresIn > 0
                    ? System.currentTimeMillis() + Math.max(0, expiresIn - EXPIRY_SAFETY_MARGIN_SECONDS) * 1000L
                    : System.currentTimeMillis(); // unknown lifetime: treat as immediately stale, re-login each call
        } catch (JSONException e) {
            throw new InfisicalException(InfisicalException.Kind.PROTOCOL, "Could not parse login response", e);
        }
    }

    /** Return a valid token, logging in if absent or (near-)expired. Single-flight via {@link #tokenLock}. */
    private String token() throws InfisicalException {
        synchronized (tokenLock) {
            if (accessToken == null || System.currentTimeMillis() >= tokenExpiresAtEpochMs) {
                login();
            }
            return accessToken;
        }
    }

    private void invalidateToken() {
        synchronized (tokenLock) {
            accessToken = null;
            tokenExpiresAtEpochMs = 0;
        }
    }

    /**
     * List all secrets at the configured project/environment/path. Re-logs in once
     * if the first attempt returns 401 (token revoked/expired between check and use).
     */
    @NonNull
    public List<InfisicalSecret> listSecrets() throws InfisicalException {
        HttpResponse<String> resp = listSecretsOnce(token());
        if (resp.statusCode() == 401) {
            // Token rejected; drop it, log in fresh, and try exactly once more.
            invalidateToken();
            resp = listSecretsOnce(token());
        }
        return parseSecretsResponse(resp);
    }

    private HttpResponse<String> listSecretsOnce(String token) throws InfisicalException {
        String query = projectParamName + "=" + enc(projectId)
                + "&environment=" + enc(environment)
                + "&secretPath=" + enc(secretPath);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v3/secrets/raw?" + query))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return send(req, "list secrets");
    }

    private List<InfisicalSecret> parseSecretsResponse(HttpResponse<String> resp) throws InfisicalException {
        int status = resp.statusCode();
        if (status == 401) {
            throw new InfisicalException(InfisicalException.Kind.AUTHENTICATION,
                    "Infisical returned 401 listing secrets even after re-login; the credentials may have been revoked.");
        }
        if (status == 403) {
            throw new InfisicalException(InfisicalException.Kind.AUTHORIZATION,
                    "Infisical returned 403: the machine identity is authenticated but lacks access to project '"
                            + projectId + "', environment '" + environment + "', path '" + secretPath + "'.");
        }
        if (status == 400 || status == 404) {
            throw new InfisicalException(InfisicalException.Kind.PROTOCOL,
                    "Infisical returned HTTP " + status + " listing secrets. Verify the project id / environment / "
                            + "secret-path query parameters for your Infisical version: " + snippet(resp.body()));
        }
        if (status / 100 != 2) {
            throw new InfisicalException(InfisicalException.Kind.PROTOCOL,
                    "Unexpected HTTP " + status + " listing secrets: " + snippet(resp.body()));
        }
        try {
            JSONObject root = JSONObject.fromObject(resp.body());
            JSONArray secrets = root.optJSONArray("secrets");
            List<InfisicalSecret> result = new ArrayList<>();
            if (secrets != null) {
                for (int i = 0; i < secrets.size(); i++) {
                    JSONObject n = secrets.getJSONObject(i);
                    String key = n.optString("secretKey", null);
                    if (key == null || key.isEmpty()) {
                        continue;
                    }
                    String value = n.optString("secretValue", "");
                    result.add(new InfisicalSecret(key, value, parseMetadata(n)));
                }
            }
            return result;
        } catch (JSONException e) {
            throw new InfisicalException(InfisicalException.Kind.PROTOCOL, "Could not parse secrets response", e);
        }
    }

    /**
     * Pull per-secret metadata into a flat map. Infisical exposes user metadata as
     * a {@code secretMetadata} array of {@code {key,value}} objects; we also fold in
     * {@code tags[].slug} as {@code tag:<slug>=true} so a plain tag can drive type
     * inference too. Used by later phases for credential-type inference.
     */
    private static Map<String, String> parseMetadata(JSONObject secretNode) {
        Map<String, String> meta = new LinkedHashMap<>();
        JSONArray md = secretNode.optJSONArray("secretMetadata");
        if (md != null) {
            for (int i = 0; i < md.size(); i++) {
                JSONObject kv = md.getJSONObject(i);
                String k = kv.optString("key", null);
                if (k != null && !k.isEmpty()) {
                    meta.put(k, kv.optString("value", ""));
                }
            }
        }
        JSONArray tags = secretNode.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.size(); i++) {
                JSONObject tag = tags.getJSONObject(i);
                String slug = tag.optString("slug", null);
                if (slug != null && !slug.isEmpty()) {
                    meta.put("tag:" + slug, "true");
                }
            }
        }
        return meta;
    }

    private HttpResponse<String> send(HttpRequest req, String what) throws InfisicalException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new InfisicalException(InfisicalException.Kind.TRANSPORT,
                    "Could not reach Infisical at " + baseUrl + " to " + what + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfisicalException(InfisicalException.Kind.TRANSPORT, "Interrupted while trying to " + what, e);
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "(no body)";
        }
        String trimmed = body.strip();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
    }

    @NonNull
    String getBaseUrl() {
        return baseUrl;
    }
}
