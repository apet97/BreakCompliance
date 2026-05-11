package me.apet97.breakcompliance.persistence.crypto;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Two-column embedded form for an AES-GCM-encrypted Clockify token: the keyId
 * that produced the ciphertext (so we can rotate keys without re-encrypting)
 * and the packed {@code IV || ciphertext+tag} bytes. Services call
 * {@link TokenCodec} explicitly to encrypt/decrypt — there is no automatic
 * JPA lifecycle hook because @Embeddables can't inject Spring beans cleanly.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EncryptedToken {

    @Column(name = "auth_token_key_id", nullable = false)
    private String keyId;

    @Column(name = "auth_token_cipher", nullable = false)
    private byte[] cipher;

    public static EncryptedToken of(TokenCodec.Encrypted encrypted) {
        return new EncryptedToken(encrypted.keyId(), encrypted.cipher());
    }
}
