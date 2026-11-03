package com.flashsale.inventory.service;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.InventoryRepository;

// Redis copy of the stock, used as a fast gate in front of the database.
// Postgres stays the source of truth; this copy can be rebuilt from it at any time.
@Service
public class RedisStockService {

    private static final RedisScript<Long> DECREASE_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/decrease_stock.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final InventoryRepository inventoryRepository;

    public RedisStockService(StringRedisTemplate redis, InventoryRepository inventoryRepository) {
        this.redis = redis;
        this.inventoryRepository = inventoryRepository;
    }

    // true = decreased in Redis; false = not enough stock.
    public boolean tryDecrease(Long productId, int quantity) {
        long result = runDecreaseScript(productId, quantity);
        if (result == -1) {
            // Not in Redis yet (never warmed, or Redis lost it): load from Postgres and try again.
            loadFromDatabase(productId);
            result = runDecreaseScript(productId, quantity);
        }
        return result == 1;
    }

    // Gives stock back after the database part of an order failed.
    public void restore(Long productId, int quantity) {
        redis.opsForValue().increment(key(productId), quantity);
    }

    public void set(Long productId, int stock) {
        redis.opsForValue().set(key(productId), String.valueOf(stock));
    }

    public void delete(Long productId) {
        redis.delete(key(productId));
    }

    private long runDecreaseScript(Long productId, int quantity) {
        return redis.execute(DECREASE_SCRIPT, List.of(key(productId)), String.valueOf(quantity));
    }

    private void loadFromDatabase(Long productId) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new NotFoundException("Inventory for product " + productId + " not found"));
        // NX: only set if still missing. When many requests miss at once, the first one wins and
        // the rest must not overwrite a value that has already been decreased.
        redis.opsForValue().setIfAbsent(key(productId), String.valueOf(inventory.getStockQuantity()));
    }

    private static String key(Long productId) {
        return "stock:" + productId;
    }
}
