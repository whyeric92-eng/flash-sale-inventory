package com.flashsale.inventory.dto;

import java.util.UUID;

public record OrderStatusResponse(UUID orderNo, String status, OrderResponse order) {
    
}
