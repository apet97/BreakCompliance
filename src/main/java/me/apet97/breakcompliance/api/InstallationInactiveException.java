package me.apet97.breakcompliance.api;

/**
 * Thrown when an API call targets a workspace whose Clockify installation is
 * {@code INACTIVE} (per the lifecycle {@code STATUS_CHANGED} contract). The
 * controller maps this to {@code 503 installation_inactive} so the sidebar
 * can render a friendly banner instead of a generic 500.
 */
public class InstallationInactiveException extends RuntimeException {

    private final String workspaceId;

    public InstallationInactiveException(String workspaceId) {
        super("installation inactive for workspaceId=" + workspaceId);
        this.workspaceId = workspaceId;
    }

    public String workspaceId() {
        return workspaceId;
    }
}
