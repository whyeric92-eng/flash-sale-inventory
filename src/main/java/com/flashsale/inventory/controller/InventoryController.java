package com.flashsale.inventory.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.flashsale.inventory.dto.InventoryRequest;
import com.flashsale.inventory.dto.InventoryResponse;
import com.flashsale.inventory.service.InventoryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{id}")
    public InventoryResponse getInventory(@PathVariable Long id) {
        return InventoryResponse.from(inventoryService.getInventory(id));
    }

    @GetMapping
    public List<InventoryResponse> getAllInventory() {
        return inventoryService.getAllInventory().stream()
                .map(InventoryResponse::from)
                .toList();
    }

    @PostMapping
    public InventoryResponse createInventory(@Valid @RequestBody InventoryRequest request) {
        return InventoryResponse.from(inventoryService.createInventory(request));
    }

    @DeleteMapping("/{id}")
    public void deleteInventory(@PathVariable Long id) {
        inventoryService.deleteInventory(id);
    }
}
