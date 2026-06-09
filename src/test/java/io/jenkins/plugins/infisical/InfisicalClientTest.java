package io.jenkins.plugins.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.Secret;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Phase 2: client behaviour against the mock — happy path, token reuse vs. expiry,
 * one-shot 401 re-login, 403 authorization, login auth failure, and slug/UUID param.
 * Uses {@code @WithJenkins} only so {@link Secret} can round-trip plaintext.
 */
@WithJenkins
class InfisicalClientTest {

    private static InfisicalClient client(MockInfisicalServer mock, String project) {
        return new InfisicalClient(mock.baseUrl(), project, "prod", "/jenkins",
                "client-id", Secret.fromString("client-secret"));
    }

    @Test
    void happyPathListsSecrets(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer()
                .addSecret("GITHUB_COM_TOKEN", "ghp_xxx")
                .addSecret("DB_PASSWORD", "hunter2")) {
            List<InfisicalSecret> secrets = client(mock, "my-project").listSecrets();
            assertEquals(2, secrets.size());
            assertEquals("ghp_xxx",
                    secrets.stream().filter(s -> s.getKey().equals("GITHUB_COM_TOKEN")).findFirst().orElseThrow().getValue());
        }
    }

    @Test
    void reusesTokenWhileValid(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer().expiresIn(3600).addSecret("X", "y")) {
            InfisicalClient c = client(mock, "my-project");
            c.listSecrets();
            c.listSecrets();
            assertEquals(1, mock.loginCount(), "token should be reused while valid");
            assertEquals(2, mock.listCount());
        }
    }

    @Test
    void reloginsWhenTokenExpired(JenkinsRule j) throws Exception {
        // expiresIn below the 60s safety margin => token is treated as immediately stale.
        try (MockInfisicalServer mock = new MockInfisicalServer().expiresIn(1).addSecret("X", "y")) {
            InfisicalClient c = client(mock, "my-project");
            c.listSecrets();
            c.listSecrets();
            assertEquals(2, mock.loginCount(), "expired token should force a re-login");
        }
    }

    @Test
    void reloginsOnceOn401(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer().expiresIn(3600).addSecret("X", "y")) {
            InfisicalClient c = client(mock, "my-project");
            mock.failNextListWith401();
            List<InfisicalSecret> secrets = c.listSecrets();
            assertEquals(1, secrets.size(), "should succeed after re-login");
            assertEquals(2, mock.loginCount(), "401 should trigger exactly one re-login");
            assertEquals(2, mock.listCount());
        }
    }

    @Test
    void forbiddenMapsToAuthorization(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer().addSecret("X", "y")) {
            mock.listReturns403(true);
            InfisicalException e = assertThrows(InfisicalException.class, () -> client(mock, "my-project").listSecrets());
            assertEquals(InfisicalException.Kind.AUTHORIZATION, e.getKind());
        }
    }

    @Test
    void badCredentialsMapToAuthentication(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer().credentials("real-id", "real-secret").addSecret("X", "y")) {
            InfisicalClient c = new InfisicalClient(mock.baseUrl(), "my-project", "prod", "/jenkins",
                    "wrong-id", Secret.fromString("wrong-secret"));
            InfisicalException e = assertThrows(InfisicalException.class, c::listSecrets);
            assertEquals(InfisicalException.Kind.AUTHENTICATION, e.getKind());
        }
    }

    @Test
    void slugIsSentAsWorkspaceSlugAndUuidAsWorkspaceId(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer().addSecret("X", "y")) {
            client(mock, "my-project").listSecrets();
            assertTrue(mock.lastListQuery().contains("workspaceSlug=my-project"), mock.lastListQuery());

            String uuid = "1c1c1c1c-2d2d-3e3e-4f4f-555566667777";
            client(mock, uuid).listSecrets();
            assertTrue(mock.lastListQuery().contains("workspaceId=" + uuid), mock.lastListQuery());
        }
    }
}
