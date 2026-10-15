package com.flashsale.inventory.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        // Base64-encoded HMAC key, at least 256 bits.
        @NotBlank String secret,
        @Positive long expirationMinutes) {
}
