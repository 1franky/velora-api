package mx.velora.api.integration.strava;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private final TokenCipher cipher = new TokenCipher("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    private final UUID user = UUID.randomUUID();

    @Test
    void roundTripsWithADifferentIvEachTime() {
        var first = cipher.encrypt(user, "token-secreto");
        var second = cipher.encrypt(user, "token-secreto");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(user, first)).isEqualTo("token-secreto");
        assertThat(new String(first, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("token-secreto");
    }

    @Test
    void aTokenCopiedToAnotherUserDoesNotDecrypt() {
        var sealed = cipher.encrypt(user, "token-secreto");
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), sealed)).isInstanceOf(IllegalStateException.class);
    }
}
