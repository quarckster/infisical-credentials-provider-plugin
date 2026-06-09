package io.jenkins.plugins.infisical;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.CredentialsStoreAction;
import com.cloudbees.plugins.credentials.domains.Domain;
import org.jspecify.annotations.NonNull;
import hudson.model.ModelObject;
import hudson.security.Permission;
import java.util.List;
import jenkins.model.Jenkins;
import org.springframework.security.core.Authentication;

/**
 * Read-only {@link CredentialsStore} that surfaces the Infisical-backed credentials
 * on the global {@code /credentials/} page. Values stay masked and lazily fetched;
 * the store advertises only view/use permissions, so the UI offers no add/update/
 * delete affordances, and the mutating operations throw.
 */
public class InfisicalCredentialsStore extends CredentialsStore {

    private final InfisicalCredentialsProvider provider;
    private final InfisicalCredentialsStoreAction action;

    InfisicalCredentialsStore(InfisicalCredentialsProvider provider) {
        super(InfisicalCredentialsProvider.class);
        this.provider = provider;
        this.action = new InfisicalCredentialsStoreAction(this);
    }

    @NonNull
    @Override
    public ModelObject getContext() {
        return Jenkins.get();
    }

    @Override
    public boolean hasPermission2(@NonNull Authentication a, @NonNull Permission permission) {
        // Read-only: grant only view/use, and defer the real decision to the global ACL.
        // CREATE/UPDATE/DELETE/MANAGE_DOMAINS are never granted, which also keeps
        // isDomainsModifiable() false and hides mutation affordances in the UI.
        return (CredentialsProvider.VIEW.equals(permission)
                        || CredentialsProvider.USE_ITEM.equals(permission)
                        || CredentialsProvider.USE_OWN.equals(permission))
                && Jenkins.get().getACL().hasPermission2(a, permission);
    }

    @NonNull
    @Override
    public List<Credentials> getCredentials(@NonNull Domain domain) {
        if (Domain.global().equals(domain) && Jenkins.get().hasPermission(CredentialsProvider.VIEW)) {
            return provider.buildCredentials();
        }
        return List.of();
    }

    @Override
    public boolean addCredentials(@NonNull Domain domain, @NonNull Credentials credentials) {
        throw new UnsupportedOperationException("The Infisical credentials store is read-only.");
    }

    @Override
    public boolean removeCredentials(@NonNull Domain domain, @NonNull Credentials credentials) {
        throw new UnsupportedOperationException("The Infisical credentials store is read-only.");
    }

    @Override
    public boolean updateCredentials(@NonNull Domain domain, @NonNull Credentials current,
                                     @NonNull Credentials replacement) {
        throw new UnsupportedOperationException("The Infisical credentials store is read-only.");
    }

    @NonNull
    @Override
    public CredentialsStoreAction getStoreAction() {
        return action;
    }
}
