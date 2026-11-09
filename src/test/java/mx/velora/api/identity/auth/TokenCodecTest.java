package mx.velora.api.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class TokenCodecTest {

    @Test
    void generatesUrlSafeTokensOf256Bits() {
        var token = TokenCodec.generate();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void generatesDistinctTokens() {
        var tokens = new HashSet<String>();
        for (int i = 0; i < 1_000; i++) {
            tokens.add(TokenCodec.generate());
        }

        assertThat(tokens).hasSize(1_000);
    }

    @Test
    void hashIsDeterministicSha256() {
        var token = TokenCodec.generate();

        assertThat(TokenCodec.hash(token)).hasSize(32).isEqualTo(TokenCodec.hash(token));
        assertThat(TokenCodec.hash(token)).isNotEqualTo(TokenCodec.hash(TokenCodec.generate()));
    }
}
