package me.apet97.breakcompliance.addon.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Test-only helper that forges Clockify-shaped JWTs with a deterministic
 * RSA keypair generated once at class load. The public key is registered as
 * a {@code @Primary} bean via {@link TestClockifyKeyConfig} so the SDK's
 * signature parser verifies tokens signed by the matching private key here.
 *
 * <p>Defaults match a valid INSTALLED token (workspaceId, addonId,
 * type=addon, iss=clockify, sub=break-compliance, exp +1h). Override any
 * default by passing the claim in the input map.
 */
public final class TestJwtForger {

    private static final KeyPair KEYPAIR = newKeyPair();
    public static final RSAPublicKey PUBLIC_KEY = (RSAPublicKey) KEYPAIR.getPublic();
    public static final RSAPrivateKey PRIVATE_KEY = (RSAPrivateKey) KEYPAIR.getPrivate();

    public static final String MANIFEST_KEY = "break-compliance";
    public static final String DEFAULT_WORKSPACE_ID = "ws-test";
    public static final String DEFAULT_ADDON_ID = "69e81390556e8f94308aaad8";
    public static final String DEFAULT_BACKEND_URL = "https://api.clockify.me/api";

    private TestJwtForger() {
    }

    public static String forgeInstalledToken() {
        return forge(Map.of(), Instant.now(), Instant.now().plus(Duration.ofHours(1)));
    }

    public static String forge(Map<String, Object> overrides) {
        return forge(overrides, Instant.now(), Instant.now().plus(Duration.ofHours(1)));
    }

    public static String forge(Map<String, Object> overrides, Instant issuedAt, Instant expiresAt) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("workspaceId", DEFAULT_WORKSPACE_ID);
        claims.put("addonId", DEFAULT_ADDON_ID);
        claims.put("backendUrl", DEFAULT_BACKEND_URL);
        claims.put("type", "addon");
        claims.putAll(overrides);

        return Jwts.builder()
                .setIssuer("clockify")
                .setSubject(MANIFEST_KEY)
                .addClaims(claims)
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .signWith(PRIVATE_KEY, SignatureAlgorithm.RS256)
                .compact();
    }

    public static String forgeWithoutExp(Map<String, Object> overrides) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("workspaceId", DEFAULT_WORKSPACE_ID);
        claims.put("addonId", DEFAULT_ADDON_ID);
        claims.put("backendUrl", DEFAULT_BACKEND_URL);
        claims.put("type", "addon");
        claims.putAll(overrides);
        return Jwts.builder()
                .setIssuer("clockify")
                .setSubject(MANIFEST_KEY)
                .addClaims(claims)
                .setIssuedAt(Date.from(Instant.now()))
                .signWith(PRIVATE_KEY, SignatureAlgorithm.RS256)
                .compact();
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA keypair generator not available", e);
        }
    }
}
