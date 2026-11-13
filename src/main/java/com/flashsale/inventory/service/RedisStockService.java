package com.flashsale.inventory.service;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.flashsale.inventory.dto.OrderMessage;
import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.InventoryRepository;

import tools.jackson.databind.json.JsonMapper;

// Redis copy of the stock, used as a fast gate in front of the database.
// Postgres stays the source of truth; this copy can be rebuilt from it at any time.
@Service
public class RedisStockService {

    private static final RedisScript<Long> DECREASE_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/decrease_stock.lua"), Long.class);

    private static final RedisScript<Long> DECREASE_AND_ENQUEUE_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/decrease_stock_and_enqueue.lua"), Long.class);

    // Public so the consumer (6.3) can read from the same list.
    public static final String QUEUE_KEY = "order:queue";

    private final StringRedisTemplate redis;
    private final InventoryRepository inventoryRepository;
    private final JsonMapper jsonMapper;

    public RedisStockService(StringRedisTemplate redis, InventoryRepository inventoryRepository,
            JsonMapper jsonMapper) {
        this.redis = redis;
        this.inventoryRepository = inventoryRepository;
        this.jsonMapper = jsonMapper;
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

    // true = stock decreased in Redis AND the message is on the queue; false = not enough stock
    // (nothing changed, nothing queued).
    public boolean tryDecreaseAndEnqueue(OrderMessage message) {
        String json = jsonMapper.writeValueAsString(message);
        long result = runDecreaseAndEnqueueScript(message.productId(), message.quantity(), json);
        if (result == -1) {
            // Not in Redis yet (never warmed, or Redis lost it): load from Postgres and try again.
            loadFromDatabase(message.productId());
            result = runDecreaseAndEnqueueScript(message.productId(), message.quantity(), json);
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

    private long runDecreaseAndEnqueueScript(Long productId, int quantity, String message) {
        return redis.execute(DECREASE_AND_ENQUEUE_SCRIPT,
                List.of(key(productId), QUEUE_KEY),
                String.valueOf(quantity), message);
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
