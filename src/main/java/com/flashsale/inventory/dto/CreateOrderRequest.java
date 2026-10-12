package com.flashsale.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
        @NotNull Long productId,
        @NotNull @Positive Integer quantity) {
}
