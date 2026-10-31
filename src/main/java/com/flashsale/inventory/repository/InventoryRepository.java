package com.flashsale.inventory.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.flashsale.inventory.entity.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductId(Long productId);

    boolean existsByProductId(Long productId);

    // Check and decrement in one statement, so concurrent orders can't both read the same old value.
    // Returns the number of rows updated: 1 = success, 0 = not enough stock (or no such product).
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.stockQuantity = i.stockQuantity - :quantity
            WHERE i.product.id = :productId
              AND i.stockQuantity >= :quantity
            """)
    int decreaseStockIfEnough(@Param("productId") Long productId, @Param("quantity") int quantity);
}
