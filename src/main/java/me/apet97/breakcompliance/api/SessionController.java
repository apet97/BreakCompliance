package me.apet97.breakcompliance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import me.apet97.breakcompliance.addon.auth.RequestAttributes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the verified user-iframe claims to the sidebar JS so admins can
 * be gated client-side (defense-in-depth — the server gates separately on
 * workspaceRole). Returning the raw token is deliberately avoided.
 */
@RestController
public class SessionController {

    @GetMapping("/api/session")
    public ResponseEntity<Map<String, Object>> session(HttpServletRequest request) {
        NormalizedClaims claims = RequestAttributes.claims(request);
        if (claims == null) {
            return ResponseEntity.status(401).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", claims.workspaceId());
        body.put("userId", claims.userId());
        body.put("workspaceRole", claims.workspaceRole());
        return ResponseEntity.ok(body);
    }
}
