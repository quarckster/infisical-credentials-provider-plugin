package io.jenkins.plugins.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.ExtensionList;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Phase 4: read-only store visible on /credentials/, only at the Jenkins root. */
@WithJenkins
class StoreVisibilityTest {

    private static InfisicalCredentialsProvider configure(MockInfisicalServer mock) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(CredentialsScope.SYSTEM, "infisical-auth", "boot",
                        "client-id", "client-secret"));
        SystemCredentialsProvider.getInstance().save();
        InfisicalGlobalConfiguration cfg = InfisicalGlobalConfiguration.get();
        cfg.setServerUrl(mock.baseUrl());
        cfg.setProjectId("my-project");
        cfg.setEnvironment("prod");
        cfg.setSecretPath("/jenkins");
        cfg.setCredentialsId("infisical-auth");
        return ExtensionList.lookupSingleton(InfisicalCredentialsProvider.class);
    }

    @Test
    void storeIsContributedOnlyAtRootAndIsReadOnly(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer().addSecret("API_TOKEN", "supersecret-value")) {
            InfisicalCredentialsProvider p = configure(mock);

            CredentialsStore store = p.getStore(j.jenkins);
            assertSame(InfisicalCredentialsStore.class, store.getClass());
            assertNull(p.getStore(j.createFreeStyleProject("x")), "no store for non-root contexts");

            assertEquals(1, store.getCredentials(Domain.global()).size());
            assertFalse(store.isDomainsModifiable(), "store must be read-only");
            assertThrows(UnsupportedOperationException.class,
                    () -> store.addCredentials(Domain.global(),
                            new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "x", "d", "u", "p")));
        }
    }

    @Test
    void secretsVisibleButValuesMaskedOnCredentialsPage(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer().addSecret("API_TOKEN", "supersecret-value")) {
            configure(mock);
            JenkinsRule.WebClient wc = j.createWebClient();
            String page = wc.goTo("credentials/").getWebResponse().getContentAsString();
            assertTrue(page.contains("Infisical"), "store labelled on the credentials page");
            assertTrue(page.contains("API_TOKEN"), "secret id/name listed");
            assertFalse(page.contains("supersecret-value"), "secret value must not be rendered");
        }
    }
}
