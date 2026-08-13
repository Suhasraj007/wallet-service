package com.wallet.web;

import com.wallet.auth.JwtService;
import com.wallet.dto.TokenRequest;
import com.wallet.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo stand-in for an identity provider so evaluators can mint tokens for
 * brand-new users. The wallet endpoints never trust this endpoint's input -
 * only the signed token it produces.
 */
@RestController
public class TokenController {

    private final JwtService jwtService;

    public TokenController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/auth/token")
    public TokenResponse issue(@Valid @RequestBody TokenRequest request) {
        String token = jwtService.issue(request.userId());
        return new TokenResponse(request.userId(), token, jwtService.ttlSeconds());
    }
}
