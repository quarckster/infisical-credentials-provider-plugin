package io.jenkins.plugins.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Phase 1 acceptance: picker, Test connection, and blank-field validation. */
@WithJenkins
class ConfigurationUxTest {

    private static void addSystemUsernamePassword(String id, String user, String pass) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(CredentialsScope.SYSTEM, id, "desc", user, pass));
        SystemCredentialsProvider.getInstance().save();
    }

    @Test
    void pickerListsUsernamePasswordCredentials(JenkinsRule j) throws Exception {
        addSystemUsernamePassword("infisical-auth", "client-id", "client-secret");
        ListBoxModel items = InfisicalGlobalConfiguration.get().doFillCredentialsIdItems(null, "");
        assertTrue(items.stream().anyMatch(o -> "infisical-auth".equals(o.value)),
                "picker should list the system username/password credential");
    }

    @Test
    void validationFlagsBlanksOnlyWhenInUse(JenkinsRule j) {
        InfisicalGlobalConfiguration cfg = InfisicalGlobalConfiguration.get();
        // All blank: plugin not in use, no error (so unrelated admins can still save).
        assertEquals(FormValidation.Kind.OK,
                cfg.doCheckProjectId("", "", "", "").kind);
        // Blank projectId while other fields are set: flagged required.
        assertEquals(FormValidation.Kind.ERROR,
                cfg.doCheckProjectId("", "https://infisical.example.com", "prod", "infisical-auth").kind);
        // Bad URL scheme flagged.
        assertEquals(FormValidation.Kind.ERROR,
                cfg.doCheckServerUrl("ftp://nope", "p", "prod", "infisical-auth").kind);
    }

    @Test
    void testConnectionReportsSecretCount(JenkinsRule j) throws Exception {
        addSystemUsernamePassword("infisical-auth", "client-id", "client-secret");
        try (MockInfisicalServer mock = new MockInfisicalServer()
                .credentials("client-id", "client-secret")
                .addSecret("GITHUB_COM_TOKEN", "ghp_xxx")
                .addSecret("DB_PASSWORD", "hunter2")) {
            FormValidation result = InfisicalGlobalConfiguration.get().doTestConnection(
                    mock.baseUrl(), "proj-123", "prod", "/jenkins", "infisical-auth");
            assertEquals(FormValidation.Kind.OK, result.kind, result.getMessage());
            assertTrue(result.getMessage().contains("2 secret"), result.getMessage());
        }
    }

    @Test
    void testConnectionReportsAuthFailure(JenkinsRule j) throws Exception {
        addSystemUsernamePassword("infisical-auth", "wrong-id", "wrong-secret");
        try (MockInfisicalServer mock = new MockInfisicalServer()
                .credentials("client-id", "client-secret")
                .addSecret("X", "y")) {
            FormValidation result = InfisicalGlobalConfiguration.get().doTestConnection(
                    mock.baseUrl(), "proj-123", "prod", "/jenkins", "infisical-auth");
            assertEquals(FormValidation.Kind.ERROR, result.kind);
            assertTrue(result.getMessage().contains("AUTHENTICATION"), result.getMessage());
        }
    }
}
