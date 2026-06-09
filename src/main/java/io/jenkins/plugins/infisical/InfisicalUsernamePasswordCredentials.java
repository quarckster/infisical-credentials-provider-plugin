package io.jenkins.plugins.infisical;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import org.jspecify.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;
import java.util.function.Supplier;

/**
 * Username/password credential backed by an Infisical secret: the secret value is
 * the password (resolved lazily); the username is non-secret metadata carried on
 * the credential. Usable with {@code gitUsernamePassword} and any
 * username/password consumer.
 */
public class InfisicalUsernamePasswordCredentials extends BaseStandardCredentials
        implements StandardUsernamePasswordCredentials {

    private static final long serialVersionUID = 1L;

    private final String username;
    private final transient Supplier<String> password;

    public InfisicalUsernamePasswordCredentials(CredentialsScope scope, String id, String description,
                                                 @NonNull String username, @NonNull Supplier<String> password) {
        super(scope, id, description);
        this.username = username;
        this.password = password;
    }

    @NonNull
    @Override
    public String getUsername() {
        return username;
    }

    @NonNull
    @Override
    public Secret getPassword() {
        return Secret.fromString(password == null ? "" : password.get());
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.InfisicalUsernamePasswordCredentials_displayName();
        }
    }
}
