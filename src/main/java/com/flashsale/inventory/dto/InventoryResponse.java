package com.flashsale.inventory.dto;

import com.flashsale.inventory.entity.Inventory;

public record InventoryResponse(Long id, Long productId, Integer stockQuantity) {

    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(
                inventory.getId(),
                inventory.getProduct().getId(),
                inventory.getStockQuantity());
    }
}
