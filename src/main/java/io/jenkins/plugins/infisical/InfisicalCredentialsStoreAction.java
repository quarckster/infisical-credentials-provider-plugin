package io.jenkins.plugins.infisical;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.CredentialsStoreAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.kohsuke.stapler.export.ExportedBean;

/** The {@code /credentials/} action that renders the read-only Infisical store. */
@ExportedBean
public class InfisicalCredentialsStoreAction extends CredentialsStoreAction {

    private static final String ICON = "symbol-credentials plugin-credentials";

    private final InfisicalCredentialsStore store;

    InfisicalCredentialsStoreAction(InfisicalCredentialsStore store) {
        this.store = store;
    }

    @NonNull
    @Override
    public CredentialsStore getStore() {
        return store;
    }

    @Override
    public String getIconFileName() {
        return isVisible() && store.hasPermission(CredentialsProvider.VIEW) ? ICON : null;
    }

    @Override
    public String getIconClassName() {
        return isVisible() && store.hasPermission(CredentialsProvider.VIEW) ? ICON : null;
    }

    @Override
    public String getDisplayName() {
        return "Infisical";
    }

    @Override
    public String getUrlName() {
        return "infisical";
    }
}
