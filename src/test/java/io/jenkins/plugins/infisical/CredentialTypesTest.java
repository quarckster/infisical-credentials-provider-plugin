package io.jenkins.plugins.infisical;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.ExtensionList;
import hudson.security.ACL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Phase 3: one secret of each type yields one credential of each type; no recursion. */
@WithJenkins
class CredentialTypesTest {

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

    private <C extends com.cloudbees.plugins.credentials.Credentials> List<C> ours(
            InfisicalCredentialsProvider p, Class<C> type, JenkinsRule j) {
        return p.getCredentialsInItemGroup(type, j.jenkins, ACL.SYSTEM2, List.of());
    }

    @Test
    void oneSecretPerTypeProducesOneCredentialPerType(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer()
                .addSecret("API_TOKEN", "tok")
                .addSecret("GIT_HTTP", "ghp_pw", Map.of("jenkins-type", "usernamePassword", "jenkins-username", "gituser"))
                .addSecret("DEPLOY_KEY", "-----BEGIN KEY-----", Map.of("jenkins-type", "sshPrivateKey", "jenkins-username", "git"))
                .addSecret("KUBECONFIG", "apiVersion: v1", Map.of("jenkins-type", "file", "jenkins-filename", "config"))) {
            InfisicalCredentialsProvider p = configure(mock);

            List<StringCredentials> strings = ours(p, StringCredentials.class, j);
            assertEquals(1, strings.size());
            assertEquals("API_TOKEN", strings.get(0).getId());
            assertEquals("tok", strings.get(0).getSecret().getPlainText());

            List<StandardUsernamePasswordCredentials> ups = ours(p, StandardUsernamePasswordCredentials.class, j);
            assertEquals(1, ups.size());
            assertEquals("gituser", ups.get(0).getUsername());
            assertEquals("ghp_pw", ups.get(0).getPassword().getPlainText());

            List<SSHUserPrivateKey> ssh = ours(p, SSHUserPrivateKey.class, j);
            assertEquals(1, ssh.size());
            assertEquals("git", ssh.get(0).getUsername());
            assertEquals("-----BEGIN KEY-----", ssh.get(0).getPrivateKeys().get(0));

            List<FileCredentials> files = ours(p, FileCredentials.class, j);
            assertEquals(1, files.size());
            assertEquals("config", files.get(0).getFileName());
            assertEquals("apiVersion: v1", new String(files.get(0).getContent().readAllBytes(), UTF_8));
        }
    }

    @Test
    void noRecursionWhenBootstrapIsUsernamePassword(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer()
                .addSecret("GIT_HTTP", "ghp_pw", Map.of("jenkins-type", "usernamePassword"))) {
            configure(mock);

            // Framework-wide lookup across ALL providers, including ours, for the very type
            // our provider now supplies — and the type of our own bootstrap credential.
            // If the bootstrap resolution re-entered this provider, this would StackOverflow.
            List<StandardUsernamePasswordCredentials> all = assertDoesNotThrow(() ->
                    CredentialsProvider.lookupCredentialsInItemGroup(
                            StandardUsernamePasswordCredentials.class, j.jenkins, ACL.SYSTEM2, List.of()));

            Set<String> ids = all.stream()
                    .map(c -> ((IdCredentials) c).getId())
                    .collect(Collectors.toSet());
            assertTrue(ids.contains("infisical-auth"), "system bootstrap credential present");
            assertTrue(ids.contains("GIT_HTTP"), "Infisical-supplied username/password present");
        }
    }
}
