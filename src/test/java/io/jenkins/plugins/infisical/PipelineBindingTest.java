package io.jenkins.plugins.infisical;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Phase 5 end-to-end: a Pipeline binds Infisical-backed credentials via
 * {@code withCredentials}, the bound values are usable, and they are masked in the
 * build log.
 */
@WithJenkins
class PipelineBindingTest {

    private static void configure(MockInfisicalServer mock) throws Exception {
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
    }

    @Test
    void stringSecretBindsAndIsMasked(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer().addSecret("API_TOKEN", "supersecret-value")) {
            configure(mock);
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "string-pipe");
            job.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                            + "  withCredentials([string(credentialsId: 'API_TOKEN', variable: 'SECRET')]) {\n"
                            + "    sh 'echo bound=$SECRET'\n"
                            + "  }\n"
                            + "}\n",
                    true));
            WorkflowRun run = j.buildAndAssertSuccess(job);
            j.assertLogNotContains("supersecret-value", run);
            j.assertLogContains("bound=****", run);
        }
    }

    @Test
    void usernamePasswordBindsAndPasswordIsMasked(JenkinsRule j) throws Exception {
        try (MockInfisicalServer mock = new MockInfisicalServer()
                .addSecret("GIT_HTTP", "ghp_supersecret",
                        Map.of("jenkins-type", "usernamePassword", "jenkins-username", "gituser"))) {
            configure(mock);
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "userpass-pipe");
            job.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                            + "  withCredentials([usernamePassword(credentialsId: 'GIT_HTTP', "
                            + "usernameVariable: 'U', passwordVariable: 'P')]) {\n"
                            + "    sh 'echo user=$U pass=$P'\n"
                            + "  }\n"
                            + "}\n",
                    true));
            WorkflowRun run = j.buildAndAssertSuccess(job);
            // credentials-binding masks both username and password in the log; the binding
            // working end-to-end + the raw password being absent is what matters here.
            // (Username value correctness is asserted directly in CredentialTypesTest.)
            j.assertLogNotContains("ghp_supersecret", run);
            j.assertLogContains("pass=****", run);
        }
    }
}
