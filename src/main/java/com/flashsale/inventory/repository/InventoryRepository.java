package com.flashsale.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.flashsale.inventory.entity.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
}
