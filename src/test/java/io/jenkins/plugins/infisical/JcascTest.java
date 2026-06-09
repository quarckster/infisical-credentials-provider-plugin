package io.jenkins.plugins.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Phase 6: the global configuration round-trips through Configuration as Code. */
@WithJenkins
class JcascTest {

    @Test
    void appliesYamlAndReadsBack(JenkinsRule j) throws Exception {
        ConfigurationAsCode.get().configure(getClass().getResource("jcasc.yml").toString());

        InfisicalGlobalConfiguration cfg = InfisicalGlobalConfiguration.get();
        assertEquals("https://infisical.example.com", cfg.getServerUrl());
        assertEquals("my-project", cfg.getProjectId());
        assertEquals("prod", cfg.getEnvironment());
        assertEquals("/ci", cfg.getSecretPath());
        assertEquals("infisical-machine-identity", cfg.getCredentialsId());
    }

    @Test
    void exportsConfiguration(JenkinsRule j) throws Exception {
        ConfigurationAsCode.get().configure(getClass().getResource("jcasc.yml").toString());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigurationAsCode.get().export(out);
        String yaml = out.toString(StandardCharsets.UTF_8);

        assertTrue(yaml.contains("infisical"), yaml);
        assertTrue(yaml.contains("my-project"), yaml);
        assertTrue(yaml.contains("/ci"), yaml);
    }
}
