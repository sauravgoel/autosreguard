package io.autosreguard.remediation;

/**
 * Integration seam between AutoSREGuard's decisions and your infrastructure.
 *
 * <p>Implement this against Kubernetes, your deploy system, your config store, your traffic
 * manager. Implementations must be idempotent: the engine may retry, and an action applied
 * twice must leave the system in the same state as once.
 */
@FunctionalInterface
public interface ActionExecutor {

    /**
     * Applies the action. Should return only once the change has been accepted by the
     * underlying system (not necessarily once its effects are visible).
     *
     * @throws RemediationException if the action could not be applied
     */
    void execute(RemediationAction action) throws RemediationException;

    /** An executor that does nothing: for dry runs and shadow mode. */
    static ActionExecutor dryRun() {
        return action -> { };
    }
}
