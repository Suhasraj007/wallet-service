package com.wallet.dto;

public record TokenResponse(String userId, String token, long expiresInSeconds) {
}
