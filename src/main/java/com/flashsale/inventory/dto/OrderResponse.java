package com.flashsale.inventory.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.flashsale.inventory.entity.Order;
import com.flashsale.inventory.entity.OrderStatus;

public record OrderResponse(
        Long id,
        Long userId,
        Long productId,
        Integer quantity,
        OrderStatus status,
        OffsetDateTime createdAt,
        UUID orderNo) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUser().getId(),
                order.getProduct().getId(),
                order.getQuantity(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getOrderNo());
    }
}
