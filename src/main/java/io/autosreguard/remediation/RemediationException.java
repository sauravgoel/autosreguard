package io.autosreguard.remediation;

import java.io.Serial;

/** Thrown when an {@link ActionExecutor} fails to apply an action. */
public class RemediationException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public RemediationException(String message) {
        super(message);
    }

    public RemediationException(String message, Throwable cause) {
        super(message, cause);
    }
}
