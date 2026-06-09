package io.jenkins.plugins.infisical;

import org.jspecify.annotations.NonNull;

/**
 * Checked exception for all Infisical client failures, tagged with a {@link Kind}
 * so callers can distinguish a misconfiguration from a bad credential from a
 * network problem and surface an actionable message.
 */
public class InfisicalException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Plugin is not configured enough to attempt a call (blank URL, project, credential, …). */
        CONFIGURATION,
        /** Universal Auth login rejected the clientId/clientSecret (HTTP 401/403 on login). */
        AUTHENTICATION,
        /** Authenticated, but the machine identity lacks access to the project/path (HTTP 403). */
        AUTHORIZATION,
        /** Server reachable but the response was not what we expected (bad status, unparseable body, wrong query param). */
        PROTOCOL,
        /** Could not reach the server at all (DNS, connect, timeout, interrupt). */
        TRANSPORT
    }

    private final Kind kind;

    public InfisicalException(@NonNull Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public InfisicalException(@NonNull Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    @NonNull
    public Kind getKind() {
        return kind;
    }
}
