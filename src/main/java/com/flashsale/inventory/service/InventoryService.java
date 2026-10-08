package com.flashsale.inventory.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.flashsale.inventory.dto.InventoryRequest;
import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.exception.ConflictException;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.InventoryRepository;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ProductService productService;

    public InventoryService(InventoryRepository inventoryRepository, ProductService productService) {
        this.inventoryRepository = inventoryRepository;
        this.productService = productService;
    }

    public Inventory getInventory(Long id) {
        return inventoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Inventory " + id + " not found"));
    }

    public List<Inventory> getAllInventory() {
        return inventoryRepository.findAll();
    }

    public Inventory createInventory(InventoryRequest request) {
        Inventory inventory = new Inventory();
        inventory.setProduct(productService.getProduct(request.productId()));
        inventory.setStockQuantity(request.stockQuantity());
        return inventoryRepository.save(inventory);
    }

    public void decreaseStock(Long productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new NotFoundException("Inventory for product " + productId + " not found"));
        int remaining = inventory.getStockQuantity() - quantity;
        if (remaining < 0) {
            throw new ConflictException("Insufficient stock");
        }
        inventory.setStockQuantity(remaining);
        inventoryRepository.save(inventory);
    }

    public void deleteInventory(Long id) {
        if (!inventoryRepository.existsById(id)) {
            throw new NotFoundException("Inventory " + id + " not found");
        }
        inventoryRepository.deleteById(id);
    }
}
