package io.jenkins.plugins.infisical;

import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import java.util.Collections;
import java.util.Map;

/**
 * One secret as returned by Infisical's raw-secrets endpoint: a key, its value,
 * and any associated metadata (tags / secret-metadata key-value pairs). The
 * metadata is what later phases use to infer the Jenkins credential type.
 */
public final class InfisicalSecret {

    private final String key;
    private final String value;
    private final Map<String, String> metadata;

    public InfisicalSecret(@NonNull String key, @Nullable String value, @Nullable Map<String, String> metadata) {
        this.key = key;
        this.value = value;
        this.metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
    }

    @NonNull
    public String getKey() {
        return key;
    }

    @Nullable
    public String getValue() {
        return value;
    }

    /** Immutable; never null. */
    @NonNull
    public Map<String, String> getMetadata() {
        return metadata;
    }
}
