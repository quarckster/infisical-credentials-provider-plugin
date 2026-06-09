package io.jenkins.plugins.infisical;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import org.jspecify.annotations.NonNull;
import hudson.Extension;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;

/**
 * Secret-file credential backed by an Infisical secret: the secret value is the
 * file content (resolved lazily and exposed as a stream); the file name is
 * non-secret metadata. Usable with {@code withCredentials([file(...)])}.
 */
public class InfisicalFileCredentials extends BaseStandardCredentials implements FileCredentials {

    private static final long serialVersionUID = 1L;

    private final String fileName;
    private final transient Supplier<String> content;

    public InfisicalFileCredentials(CredentialsScope scope, String id, String description,
                                    @NonNull String fileName, @NonNull Supplier<String> content) {
        super(scope, id, description);
        this.fileName = fileName;
        this.content = content;
    }

    @NonNull
    @Override
    public String getFileName() {
        return fileName;
    }

    @NonNull
    @Override
    public InputStream getContent() {
        String value = content == null ? "" : content.get();
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.InfisicalFileCredentials_displayName();
        }
    }
}
