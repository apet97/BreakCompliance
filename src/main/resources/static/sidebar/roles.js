import { state } from "./state.js";

// True only when the session's workspaceRole resolves to a Clockify admin or
// owner. Anything else (MEMBER, missing claim, unexpected value) returns false
// — fail-closed, matching the server's RequestValidator.requireAdmin.
export function isAdmin() {
    const role = String(state.session?.workspaceRole ?? "").toUpperCase();
    return role === "ADMIN" || role === "OWNER";
}
