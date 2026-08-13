package com.wallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wallet.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef-xyz";

    @Test
    void issuedTokenVerifiesBackToTheSameUser() {
        JwtService jwt = new JwtService(SECRET, 3600);
        String token = jwt.issue("alice");
        assertThat(jwt.verify(token)).isEqualTo("alice");
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwt = new JwtService(SECRET, 3600);
        String token = jwt.issue("alice");
        assertThatThrownBy(() -> jwt.verify(token + "x"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtService issuer = new JwtService("some-other-32-byte-secret-abcdefgh", 3600);
        JwtService verifier = new JwtService(SECRET, 3600);
        String token = issuer.issue("alice");
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void garbageTokenIsRejected() {
        JwtService jwt = new JwtService(SECRET, 3600);
        assertThatThrownBy(() -> jwt.verify("not.a.jwt"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void shortSecretRefusesToStart() {
        assertThatThrownBy(() -> new JwtService("too-short", 3600))
                .isInstanceOf(IllegalStateException.class);
    }
}
