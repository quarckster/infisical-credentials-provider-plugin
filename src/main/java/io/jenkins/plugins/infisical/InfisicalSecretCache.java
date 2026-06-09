package io.jenkins.plugins.infisical;

import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Short-TTL cache of the secrets fetched from Infisical.
 *
 * <p>The provider's listing methods are called very frequently (every credentials
 * dropdown, every {@code withCredentials}), so the secret set is fetched once and
 * reused for {@link #TTL_MS}. Infisical's raw endpoint returns keys and values
 * together, so we cache both; credential objects still resolve their value lazily
 * <em>from this cache</em> at {@code getSecret()} time rather than embedding it, so
 * values are never persisted and a refreshed value is picked up on the next fetch.
 *
 * <p>The cache is invalidated whenever the configuration {@link
 * InfisicalGlobalConfiguration#signature() signature} changes.
 */
class InfisicalSecretCache {

    private static final long TTL_MS = 30_000L;

    private final Object lock = new Object();
    private Map<String, InfisicalSecret> secrets = Collections.emptyMap();
    private String signature;
    private long fetchedAtMs;

    /** Fetch (or return cached) secrets keyed by secret key. Throws on a real failure. */
    @NonNull
    Map<String, InfisicalSecret> getSecrets() throws InfisicalException {
        InfisicalGlobalConfiguration cfg = InfisicalGlobalConfiguration.get();
        String sig = cfg.signature();
        synchronized (lock) {
            long now = System.currentTimeMillis();
            boolean stale = secrets.isEmpty()
                    || !Objects.equals(sig, signature)
                    || (now - fetchedAtMs) > TTL_MS;
            if (stale) {
                List<InfisicalSecret> fetched = cfg.createClient().listSecrets();
                Map<String, InfisicalSecret> m = new LinkedHashMap<>();
                for (InfisicalSecret s : fetched) {
                    m.put(s.getKey(), s);
                }
                secrets = Collections.unmodifiableMap(m);
                signature = sig;
                fetchedAtMs = now;
            }
            return secrets;
        }
    }

    /** Resolve a single secret value lazily (used by credential {@code getSecret()}). */
    @Nullable
    String getValue(@NonNull String key) throws InfisicalException {
        InfisicalSecret s = getSecrets().get(key);
        return s == null ? null : s.getValue();
    }

    /** Drop the cache so the next access refetches (e.g. after a config change or test). */
    void invalidate() {
        synchronized (lock) {
            secrets = Collections.emptyMap();
            signature = null;
            fetchedAtMs = 0;
        }
    }
}
