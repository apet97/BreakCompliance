package me.apet97.breakcompliance.persistence.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM-256 envelope for Clockify installation and webhook auth tokens.
 *
 * <p>Wire format per ciphertext: {@code IV(12) || ciphertext+tag}. The 128-bit
 * GCM auth tag is appended to the ciphertext by JCA's {@code doFinal()} and
 * verified on decrypt, so any tampering (or use of the wrong key) fails closed
 * with {@link TokenCodecException}. {@code keyId} is stored alongside the
 * ciphertext (in a sibling column) so keys can be rotated without re-encrypting
 * existing rows.
 */
public final class TokenCodec {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    public TokenCodec(Map<String, String> hexKeysById, String activeKeyId) {
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new TokenCodecException("active key id must not be blank");
        }
        if (hexKeysById == null || hexKeysById.isEmpty()) {
            throw new TokenCodecException("keys map must not be empty");
        }
        Map<String, SecretKeySpec> parsed = new HashMap<>();
        for (Map.Entry<String, String> e : hexKeysById.entrySet()) {
            parsed.put(e.getKey(), new SecretKeySpec(decodeHex(e.getKey(), e.getValue()), ALGORITHM));
        }
        if (!parsed.containsKey(activeKeyId)) {
            throw new TokenCodecException("active key id '" + activeKeyId + "' not present in keys map");
        }
        this.keys = Map.copyOf(parsed);
        this.activeKeyId = activeKeyId;
    }

    public Encrypted encrypt(String plaintext) {
        if (plaintext == null) {
            throw new TokenCodecException("plaintext must not be null");
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return new Encrypted(activeKeyId, packed);
        } catch (GeneralSecurityException e) {
            throw new TokenCodecException("encrypt failed", e);
        }
    }

    public String decrypt(String keyId, byte[] packed) {
        if (keyId == null || packed == null) {
            throw new TokenCodecException("decrypt requires keyId and ciphertext");
        }
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new TokenCodecException("unknown keyId: " + keyId);
        }
        if (packed.length < IV_BYTES + (TAG_BITS / 8)) {
            throw new TokenCodecException("ciphertext too short");
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(packed);
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new TokenCodecException("decrypt failed (possible tampering or wrong key)", e);
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    private static byte[] decodeHex(String keyId, String hex) {
        if (hex == null || hex.isBlank()) {
            throw new TokenCodecException("hex key for '" + keyId + "' is blank");
        }
        byte[] raw;
        try {
            raw = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new TokenCodecException("hex key for '" + keyId + "' is not valid hex", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new TokenCodecException(
                    "hex key for '" + keyId + "' must decode to " + KEY_BYTES + " bytes (got " + raw.length + ")");
        }
        return raw;
    }

    public record Encrypted(String keyId, byte[] cipher) {
    }
}
