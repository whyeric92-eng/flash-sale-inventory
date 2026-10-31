package com.flashsale.inventory.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flashsale.inventory.dto.InventoryRequest;
import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.exception.ConflictException;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.InventoryRepository;

@Service
@Transactional(readOnly = true)
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

    @Transactional
    public Inventory createInventory(InventoryRequest request) {
        Inventory inventory = new Inventory();
        inventory.setProduct(productService.getProduct(request.productId()));
        inventory.setStockQuantity(request.stockQuantity());
        return inventoryRepository.save(inventory);
    }

    @Transactional
    public void decreaseStock(Long productId, int quantity) {
        int updated = inventoryRepository.decreaseStockIfEnough(productId, quantity);
        if (updated == 0) {
            // 0 rows can mean "no stock left" or "no such product"; look it up only on this rare path.
            if (!inventoryRepository.existsByProductId(productId)) {
                throw new NotFoundException("Inventory for product " + productId + " not found");
            }
            throw new ConflictException("Insufficient stock");
        }
    }

    @Transactional
    public void deleteInventory(Long id) {
        if (!inventoryRepository.existsById(id)) {
            throw new NotFoundException("Inventory " + id + " not found");
        }
        inventoryRepository.deleteById(id);
    }
}
