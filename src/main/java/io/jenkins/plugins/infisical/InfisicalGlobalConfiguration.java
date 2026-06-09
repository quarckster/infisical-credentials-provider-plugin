package io.jenkins.plugins.infisical;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.List;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Global, controller-wide configuration of the single Infisical instance this
 * plugin reads from: where it is, which project/environment/path to read, and
 * which stored username/password credential carries the machine-identity
 * clientId (username) and clientSecret (password) for Universal Auth.
 */
@Extension
@Symbol("infisical")
public class InfisicalGlobalConfiguration extends GlobalConfiguration {

    private String serverUrl;
    private String projectId;
    private String environment;
    private String secretPath = "/";
    private String credentialsId;

    public InfisicalGlobalConfiguration() {
        load();
    }

    @NonNull
    public static InfisicalGlobalConfiguration get() {
        return ExtensionList.lookupSingleton(InfisicalGlobalConfiguration.class);
    }

    @CheckForNull
    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = Util.fixEmptyAndTrim(serverUrl);
        save();
    }

    @CheckForNull
    public String getProjectId() {
        return projectId;
    }

    @DataBoundSetter
    public void setProjectId(String projectId) {
        this.projectId = Util.fixEmptyAndTrim(projectId);
        save();
    }

    @CheckForNull
    public String getEnvironment() {
        return environment;
    }

    @DataBoundSetter
    public void setEnvironment(String environment) {
        this.environment = Util.fixEmptyAndTrim(environment);
        save();
    }

    @CheckForNull
    public String getSecretPath() {
        return secretPath;
    }

    @DataBoundSetter
    public void setSecretPath(String secretPath) {
        String v = Util.fixEmptyAndTrim(secretPath);
        this.secretPath = (v == null) ? "/" : v;
        save();
    }

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        save();
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) {
        // Reset then rebind so cleared fields are honoured.
        this.serverUrl = null;
        this.projectId = null;
        this.environment = null;
        this.secretPath = "/";
        this.credentialsId = null;
        req.bindJSON(this, json);
        save();
        return true;
    }

    /** True only when every field needed to attempt a connection is present. */
    public boolean isConfigured() {
        return notBlank(serverUrl) && notBlank(projectId) && notBlank(environment) && notBlank(credentialsId);
    }

    /**
     * Build a client from the current configuration, resolving the bootstrap
     * credential <em>directly from the system store</em>.
     *
     * <p>This bypass is the recursion guard: once this plugin also supplies
     * username/password credentials, a normal {@code lookupCredentials} for the
     * bootstrap id could re-enter {@link InfisicalCredentialsProvider} and recurse.
     * {@link SystemCredentialsProvider#getCredentials()} reads only the system
     * store and never consults other providers, so it can never loop back here.
     */
    @NonNull
    public InfisicalClient createClient() throws InfisicalException {
        return createClient(serverUrl, projectId, environment, secretPath, credentialsId);
    }

    /**
     * Build a client from explicit values (used by the "Test connection" button so
     * an admin can validate before saving). Resolves the bootstrap credential from
     * the system store only — the recursion guard described on {@link #createClient()}.
     */
    @NonNull
    InfisicalClient createClient(String serverUrl, String projectId, String environment,
                                 String secretPath, String credentialsId) throws InfisicalException {
        if (!(notBlank(serverUrl) && notBlank(projectId) && notBlank(environment) && notBlank(credentialsId))) {
            throw new InfisicalException(InfisicalException.Kind.CONFIGURATION,
                    "Infisical is not fully configured (need server URL, project id, environment and an auth credential).");
        }
        StandardUsernamePasswordCredentials boot = resolveBootstrapCredential(credentialsId);
        if (boot == null) {
            throw new InfisicalException(InfisicalException.Kind.CONFIGURATION,
                    "No username/password credential with id '" + credentialsId + "' found in the system store.");
        }
        String path = (secretPath == null || secretPath.isBlank()) ? "/" : secretPath;
        return new InfisicalClient(serverUrl, projectId, environment, path,
                boot.getUsername(), boot.getPassword());
    }

    /**
     * Look up the bootstrap username/password credential by id, reading only the
     * system store so this never re-enters this plugin's own provider.
     */
    @CheckForNull
    static StandardUsernamePasswordCredentials resolveBootstrapCredential(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Credentials c : SystemCredentialsProvider.getInstance().getCredentials()) {
            if (c instanceof StandardUsernamePasswordCredentials
                    && id.equals(((StandardUsernamePasswordCredentials) c).getId())) {
                return (StandardUsernamePasswordCredentials) c;
            }
        }
        return null;
    }

    /** Signature used by the secret cache to detect configuration changes. */
    @NonNull
    String signature() {
        return String.valueOf(serverUrl) + '|' + projectId + '|' + environment + '|' + secretPath + '|' + credentialsId;
    }

    // ---- Form UX (Phase 1) ----

    /** Populate the bootstrap-credential picker with system username/password credentials. */
    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
        StandardListBoxModel result = new StandardListBoxModel();
        // Global configuration has no Item ancestor; gate on ADMINISTER.
        if (item == null) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(credentialsId);
            }
        } else if (!item.hasPermission(Item.EXTENDED_READ)
                && !item.hasPermission(com.cloudbees.plugins.credentials.CredentialsProvider.USE_ITEM)) {
            return result.includeCurrentValue(credentialsId);
        }
        return result
                .includeEmptyValue()
                .includeMatchingAs(ACL.SYSTEM2, Jenkins.get(), StandardUsernamePasswordCredentials.class,
                        List.of(), CredentialsMatchers.always())
                .includeCurrentValue(credentialsId);
    }

    /** "Test connection": perform a Universal Auth login and one listSecrets, report count or error. */
    @RequirePOST
    public FormValidation doTestConnection(
            @QueryParameter String serverUrl,
            @QueryParameter String projectId,
            @QueryParameter String environment,
            @QueryParameter String secretPath,
            @QueryParameter String credentialsId) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            InfisicalClient client = createClient(serverUrl, projectId, environment, secretPath, credentialsId);
            int count = client.listSecrets().size();
            String path = (secretPath == null || secretPath.isBlank()) ? "/" : secretPath;
            return FormValidation.ok("Success — connected to Infisical and found %d secret(s) at %s.", count, path);
        } catch (InfisicalException e) {
            return FormValidation.error("%s: %s", e.getKind(), e.getMessage());
        }
    }

    public FormValidation doCheckServerUrl(@QueryParameter String value,
                                           @QueryParameter String projectId,
                                           @QueryParameter String environment,
                                           @QueryParameter String credentialsId) {
        FormValidation required = requiredWhenInUse(value, "Server URL", projectId, environment, credentialsId);
        if (required.kind != FormValidation.Kind.OK || !notBlank(value)) {
            return required;
        }
        String v = value.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            return FormValidation.error("Server URL must start with http:// or https://");
        }
        return FormValidation.ok();
    }

    public FormValidation doCheckProjectId(@QueryParameter String value,
                                           @QueryParameter String serverUrl,
                                           @QueryParameter String environment,
                                           @QueryParameter String credentialsId) {
        return requiredWhenInUse(value, "Project ID", serverUrl, environment, credentialsId);
    }

    public FormValidation doCheckEnvironment(@QueryParameter String value,
                                             @QueryParameter String serverUrl,
                                             @QueryParameter String projectId,
                                             @QueryParameter String credentialsId) {
        return requiredWhenInUse(value, "Environment", serverUrl, projectId, credentialsId);
    }

    public FormValidation doCheckCredentialsId(@QueryParameter String value,
                                               @QueryParameter String serverUrl,
                                               @QueryParameter String projectId,
                                               @QueryParameter String environment) {
        return requiredWhenInUse(value, "Auth credential", serverUrl, projectId, environment);
    }

    /**
     * Flag a blank field as required only when the plugin is being configured (any
     * sibling field is non-blank). This way an admin who does not use Infisical sees
     * no errors and can still save the global "Configure System" page.
     */
    private static FormValidation requiredWhenInUse(String value, String label, String... siblings) {
        if (notBlank(value)) {
            return FormValidation.ok();
        }
        for (String s : siblings) {
            if (notBlank(s)) {
                return FormValidation.error(label + " is required.");
            }
        }
        return FormValidation.ok();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
