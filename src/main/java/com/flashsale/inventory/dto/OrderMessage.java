package com.flashsale.inventory.dto;

import java.util.UUID;

// What goes onto the order queue: everything the consumer needs to write the order.
public record OrderMessage(
        UUID orderNo,
        Long userId,
        Long productId,
        Integer quantity) {
}
