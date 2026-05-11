package me.apet97.breakcompliance.addon.auth;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestAttributes {

    public static final String NORMALIZED_CLAIMS = "breakcompliance.normalized-claims";

    private RequestAttributes() {
    }

    public static NormalizedClaims claims(HttpServletRequest request) {
        Object value = request.getAttribute(NORMALIZED_CLAIMS);
        return value instanceof NormalizedClaims c ? c : null;
    }
}
