package io.jenkins.plugins.infisical;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.ModelObject;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.security.core.Authentication;

/**
 * Credentials provider that exposes Infisical secrets as native Jenkins
 * credentials. Listing is metadata-only and backed by a TTL cache; secret values
 * are resolved lazily when a binding actually reads them.
 *
 * <h2>Type-inference convention</h2>
 * Each Infisical secret maps to exactly one Jenkins credential. The Jenkins
 * credential <em>type</em> is chosen from the secret's {@code jenkins-type}
 * metadata value (case-insensitive), defaulting to secret text:
 * <ul>
 *   <li>{@code secretText} (or absent) → {@link InfisicalStringCredentials}</li>
 *   <li>{@code usernamePassword} → {@link InfisicalUsernamePasswordCredentials};
 *       password = secret value, username = {@code jenkins-username} metadata
 *       (default {@code x-access-token}, matching git-over-HTTPS token auth)</li>
 *   <li>{@code sshPrivateKey} → {@link InfisicalSSHUserPrivateKey}; private key =
 *       secret value, username = {@code jenkins-username} (default {@code git}),
 *       passphrase = {@code jenkins-passphrase} metadata (optional)</li>
 *   <li>{@code file} → {@link InfisicalFileCredentials}; content = secret value,
 *       file name = {@code jenkins-filename} metadata (default = the secret key)</li>
 * </ul>
 * The Jenkins credential id defaults to the secret key, overridable with
 * {@code jenkins-id}; the description with {@code jenkins-description}.
 */
@Extension
public class InfisicalCredentialsProvider extends CredentialsProvider {

    private static final Logger LOGGER = Logger.getLogger(InfisicalCredentialsProvider.class.getName());

    static final String META_TYPE = "jenkins-type";
    static final String META_ID = "jenkins-id";
    static final String META_DESCRIPTION = "jenkins-description";
    static final String META_USERNAME = "jenkins-username";
    static final String META_PASSPHRASE = "jenkins-passphrase";
    static final String META_FILENAME = "jenkins-filename";

    static final String DEFAULT_HTTP_USERNAME = "x-access-token";
    static final String DEFAULT_SSH_USERNAME = "git";

    private final InfisicalSecretCache cache = new InfisicalSecretCache();

    /** The single read-only store, contributed for the Jenkins root context. */
    private final InfisicalCredentialsStore store = new InfisicalCredentialsStore(this);

    /**
     * Re-entrancy guard. Resolving this plugin's own bootstrap credential reads the
     * system store directly (see {@link InfisicalGlobalConfiguration#createClient()}),
     * which already avoids re-entering this provider. This guard is a belt-and-braces
     * backstop: should any code path ever trigger a credentials lookup while we are
     * mid-listing, we return nothing rather than recurse.
     */
    private final ThreadLocal<Boolean> building = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @NonNull
    @Override
    public <C extends Credentials> List<C> getCredentialsInItemGroup(
            @NonNull Class<C> type,
            @Nullable ItemGroup itemGroup,
            @Nullable Authentication authentication,
            @NonNull List<DomainRequirement> domainRequirements) {
        List<C> result = new ArrayList<>();
        for (Credentials c : buildCredentials()) {
            if (type.isAssignableFrom(c.getClass())) {
                result.add(type.cast(c));
            }
        }
        return result;
    }

    @NonNull
    @Override
    public <C extends Credentials> List<C> getCredentialsInItem(
            @NonNull Class<C> type,
            @NonNull Item item,
            @Nullable Authentication authentication,
            @NonNull List<DomainRequirement> domainRequirements) {
        return getCredentialsInItemGroup(type, item.getParent(), authentication, domainRequirements);
    }

    /** Contribute the read-only store at the Jenkins root so secrets show on /credentials/. */
    @CheckForNull
    @Override
    public CredentialsStore getStore(@CheckForNull ModelObject object) {
        return object instanceof Jenkins ? store : null;
    }

    /**
     * Build the current set of Infisical-backed credentials from cached metadata.
     * Never throws: a misconfiguration or an unreachable Infisical must not break
     * the credentials page or every {@code withCredentials} lookup, so failures are
     * logged and yield an empty list.
     */
    @NonNull
    List<Credentials> buildCredentials() {
        if (Boolean.TRUE.equals(building.get())) {
            return List.of();
        }
        building.set(Boolean.TRUE);
        try {
            InfisicalGlobalConfiguration cfg = InfisicalGlobalConfiguration.get();
            if (!cfg.isConfigured()) {
                return List.of();
            }
            List<Credentials> result = new ArrayList<>();
            for (InfisicalSecret secret : cache.getSecrets().values()) {
                result.add(toCredential(secret));
            }
            return result;
        } catch (InfisicalException e) {
            LOGGER.log(Level.FINE,
                    "Could not list Infisical secrets (" + e.getKind() + "); returning no credentials this round",
                    e);
            return List.of();
        } finally {
            building.set(Boolean.FALSE);
        }
    }

    /** Map one Infisical secret to the Jenkins credential its metadata calls for. */
    @NonNull
    private Credentials toCredential(@NonNull InfisicalSecret secret) {
        String key = secret.getKey();
        Map<String, String> meta = secret.getMetadata();
        String id = orDefault(meta.get(META_ID), key);
        String description = orDefault(meta.get(META_DESCRIPTION), "Infisical: " + key);
        String type = orDefault(meta.get(META_TYPE), "secretText").trim();

        switch (type.toLowerCase()) {
            case "usernamepassword":
                String httpUser = orDefault(meta.get(META_USERNAME), DEFAULT_HTTP_USERNAME);
                return new InfisicalUsernamePasswordCredentials(
                        CredentialsScope.GLOBAL, id, description, httpUser, () -> resolve(key));
            case "sshprivatekey":
                String sshUser = orDefault(meta.get(META_USERNAME), DEFAULT_SSH_USERNAME);
                String passphrase = meta.get(META_PASSPHRASE);
                Secret pass = (passphrase == null || passphrase.isEmpty()) ? null : Secret.fromString(passphrase);
                return new InfisicalSSHUserPrivateKey(
                        CredentialsScope.GLOBAL, id, description, sshUser, pass, () -> resolve(key));
            case "file":
                String fileName = orDefault(meta.get(META_FILENAME), key);
                return new InfisicalFileCredentials(
                        CredentialsScope.GLOBAL, id, description, fileName, () -> resolve(key));
            case "secrettext":
            default:
                return new InfisicalStringCredentials(
                        CredentialsScope.GLOBAL, id, description, () -> resolve(key));
        }
    }

    /** Lazy value resolution; surfaces a clear failure if the secret can't be fetched. */
    @NonNull
    private String resolve(@NonNull String key) {
        try {
            String value = cache.getValue(key);
            return value == null ? "" : value;
        } catch (InfisicalException e) {
            throw new IllegalStateException("Failed to resolve Infisical secret '" + key + "': " + e.getMessage(), e);
        }
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
