package io.jenkins.plugins.infisical;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;
import java.util.List;
import java.util.function.Supplier;

/**
 * SSH private-key credential backed by an Infisical secret: the secret value is
 * the PEM private key (resolved lazily); username and optional passphrase are
 * non-secret metadata. Usable with {@code sshagent} / {@code sshUserPrivateKey}.
 */
public class InfisicalSSHUserPrivateKey extends BaseStandardCredentials implements SSHUserPrivateKey {

    private static final long serialVersionUID = 1L;

    private final String username;
    private final Secret passphrase;
    private final transient Supplier<String> privateKey;

    public InfisicalSSHUserPrivateKey(CredentialsScope scope, String id, String description,
                                      @NonNull String username, @Nullable Secret passphrase,
                                      @NonNull Supplier<String> privateKey) {
        super(scope, id, description);
        this.username = username;
        this.passphrase = passphrase;
        this.privateKey = privateKey;
    }

    @NonNull
    @Override
    public String getUsername() {
        return username;
    }

    @NonNull
    @Override
    @Deprecated
    public String getPrivateKey() {
        List<String> keys = getPrivateKeys();
        return keys.isEmpty() ? "" : keys.get(0);
    }

    @NonNull
    @Override
    public List<String> getPrivateKeys() {
        return List.of(privateKey == null ? "" : privateKey.get());
    }

    @Nullable
    @Override
    public Secret getPassphrase() {
        return passphrase;
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.InfisicalSSHUserPrivateKey_displayName();
        }
    }
}
