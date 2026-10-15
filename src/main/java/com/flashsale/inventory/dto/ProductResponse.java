package com.flashsale.inventory.dto;

import com.flashsale.inventory.entity.Product;

public record ProductResponse(Long id, String productName) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getProductName());
    }
}
