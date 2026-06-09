package io.jenkins.plugins.infisical;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import org.jspecify.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;
import java.util.function.Supplier;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * A Jenkins "secret text" credential backed by an Infisical secret. The value is
 * resolved lazily through a {@link Supplier} supplied by the provider, so the
 * value is never embedded in (or persisted with) the credential object — it is
 * read from the provider's TTL cache only when {@code getSecret()} is called.
 */
public class InfisicalStringCredentials extends BaseStandardCredentials implements StringCredentials {

    private static final long serialVersionUID = 1L;

    /** Transient on purpose: these credentials are always provider-supplied, never persisted. */
    private final transient Supplier<String> value;

    public InfisicalStringCredentials(CredentialsScope scope, String id, String description,
                                      @NonNull Supplier<String> value) {
        super(scope, id, description);
        this.value = value;
    }

    @NonNull
    @Override
    public Secret getSecret() {
        return Secret.fromString(value == null ? "" : value.get());
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.InfisicalStringCredentials_displayName();
        }
    }
}
