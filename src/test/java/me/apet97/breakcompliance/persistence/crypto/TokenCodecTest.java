package me.apet97.breakcompliance.persistence.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenCodecTest {

    private static final String KEY_A = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
    private static final String KEY_B = "1112131415161718191a1b1c1d1e1f200102030405060708090a0b0c0d0e0f10";

    @Test
    void roundTrip() {
        TokenCodec codec = new TokenCodec(Map.of("k1", KEY_A), "k1");
        TokenCodec.Encrypted out = codec.encrypt("hello-clockify");

        assertThat(out.keyId()).isEqualTo("k1");
        assertThat(codec.decrypt("k1", out.cipher())).isEqualTo("hello-clockify");
    }

    @Test
    void encryptProducesDifferentCiphertextsForSamePlaintext() {
        TokenCodec codec = new TokenCodec(Map.of("k1", KEY_A), "k1");

        TokenCodec.Encrypted a = codec.encrypt("same");
        TokenCodec.Encrypted b = codec.encrypt("same");

        assertThat(a.cipher()).isNotEqualTo(b.cipher());
        assertThat(codec.decrypt("k1", a.cipher())).isEqualTo("same");
        assertThat(codec.decrypt("k1", b.cipher())).isEqualTo("same");
    }

    @Test
    void supportsKeyRotation() {
        TokenCodec activeIsK2 = new TokenCodec(Map.of("k1", KEY_A, "k2", KEY_B), "k2");
        TokenCodec.Encrypted out = activeIsK2.encrypt("rotate-me");
        assertThat(out.keyId()).isEqualTo("k2");

        TokenCodec activeIsK1 = new TokenCodec(Map.of("k1", KEY_A, "k2", KEY_B), "k1");
        assertThat(activeIsK1.decrypt("k2", out.cipher())).isEqualTo("rotate-me");
    }

    @Test
    void unknownKeyIdFailsClosed() {
        TokenCodec codec = new TokenCodec(Map.of("k1", KEY_A), "k1");
        TokenCodec.Encrypted out = codec.encrypt("test");

        assertThatThrownBy(() -> codec.decrypt("nope", out.cipher()))
                .isInstanceOf(TokenCodecException.class)
                .hasMessageContaining("unknown keyId");
    }

    @Test
    void tamperedCipherFailsClosed() {
        TokenCodec codec = new TokenCodec(Map.of("k1", KEY_A), "k1");
        TokenCodec.Encrypted out = codec.encrypt("test");
        byte[] tampered = out.cipher().clone();
        tampered[tampered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> codec.decrypt("k1", tampered)).isInstanceOf(TokenCodecException.class);
    }

    @Test
    void wrongKeyBytesFailsClosed() {
        TokenCodec codec1 = new TokenCodec(Map.of("k1", KEY_A), "k1");
        TokenCodec.Encrypted out = codec1.encrypt("test");

        TokenCodec codec2 = new TokenCodec(Map.of("k1", KEY_B), "k1");

        assertThatThrownBy(() -> codec2.decrypt("k1", out.cipher())).isInstanceOf(TokenCodecException.class);
    }

    @Test
    void blankActiveKeyIdRejected() {
        assertThatThrownBy(() -> new TokenCodec(Map.of("k1", KEY_A), ""))
                .isInstanceOf(TokenCodecException.class);
    }

    @Test
    void emptyKeysMapRejected() {
        assertThatThrownBy(() -> new TokenCodec(Map.of(), "k1")).isInstanceOf(TokenCodecException.class);
    }

    @Test
    void invalidHexKeyRejected() {
        assertThatThrownBy(() -> new TokenCodec(Map.of("k1", "not-hex"), "k1"))
                .isInstanceOf(TokenCodecException.class)
                .hasMessageContaining("not valid hex");
    }

    @Test
    void shortHexKeyRejected() {
        assertThatThrownBy(() -> new TokenCodec(Map.of("k1", "01020304"), "k1"))
                .isInstanceOf(TokenCodecException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void activeKeyIdNotInMapRejected() {
        assertThatThrownBy(() -> new TokenCodec(Map.of("k1", KEY_A), "k2"))
                .isInstanceOf(TokenCodecException.class)
                .hasMessageContaining("not present");
    }
}
