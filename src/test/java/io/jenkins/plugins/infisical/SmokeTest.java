package io.jenkins.plugins.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.ExtensionList;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Phase 0 acceptance: the plugin loads, the provider extension is registered, and
 * the global configuration screen renders and round-trips its form.
 */
@WithJenkins
class SmokeTest {

    @Test
    void providerAndConfigAreRegistered(JenkinsRule j) {
        assertNotNull(InfisicalGlobalConfiguration.get(), "global configuration singleton present");
        assertTrue(
                ExtensionList.lookup(CredentialsProvider.class).stream()
                        .anyMatch(InfisicalCredentialsProvider.class::isInstance),
                "InfisicalCredentialsProvider should be a registered CredentialsProvider");
    }

    @Test
    void globalConfigScreenRendersAndRoundTrips(JenkinsRule j) throws Exception {
        InfisicalGlobalConfiguration cfg = InfisicalGlobalConfiguration.get();
        cfg.setServerUrl("https://infisical.example.com");
        cfg.setProjectId("proj-123");
        cfg.setEnvironment("prod");
        cfg.setSecretPath("/ci");

        // Submits the "Configure System" form: renders config.jelly and re-binds it.
        j.configRoundtrip();

        InfisicalGlobalConfiguration after = InfisicalGlobalConfiguration.get();
        assertEquals("https://infisical.example.com", after.getServerUrl());
        assertEquals("proj-123", after.getProjectId());
        assertEquals("prod", after.getEnvironment());
        assertEquals("/ci", after.getSecretPath());
    }
}
