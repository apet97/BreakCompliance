package me.apet97.breakcompliance.addon.auth;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Decodes a PEM-encoded RSA public key (X.509 SubjectPublicKeyInfo, base64)
 * — the shape Clockify publishes at {@code /api/auth/public-key}. Fail-closed
 * on any parse error so a downstream caller cannot accidentally configure
 * the addon with a junk key and then accept forged tokens.
 */
public final class PublicKeyParser {

    private PublicKeyParser() {
    }

    public static RSAPublicKey parsePem(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("public key PEM must not be blank");
        }
        String cleaned = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(cleaned);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("failed to parse RSA public key PEM", e);
        }
    }
}
