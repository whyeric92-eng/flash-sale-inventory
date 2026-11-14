package com.flashsale.inventory.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    private static final RedisScript<Long> DECREASE_AND_ENQUEUE_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/decrease_stock_and_enqueue.lua"), Long.class);

    private static final RedisScript<Long> RESTORE_AND_REMOVE_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/restore_stock_and_remove.lua"), Long.class);

    // Public so the consumer (6.3) can read from the same list.
    public static final String QUEUE_KEY = "order:queue";

    // How long a QUEUED/FAILED status stays readable. Orders are written within seconds, after
    // which the database answers; this only needs to outlive the queue and a user checking back.
    private static final Duration STATUS_TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;
    private final InventoryRepository inventoryRepository;
    private final JsonMapper jsonMapper;

    public RedisStockService(StringRedisTemplate redis, InventoryRepository inventoryRepository,
            JsonMapper jsonMapper) {
        this.redis = redis;
        this.inventoryRepository = inventoryRepository;
        this.jsonMapper = jsonMapper;
    }

    // true = stock decreased in Redis AND the message is on the queue; false = not enough stock
    // (nothing changed, nothing queued).
    public boolean tryDecreaseAndEnqueue(OrderMessage message) {
        String json = jsonMapper.writeValueAsString(message);
        long result = runDecreaseAndEnqueueScript(message, json);
        if (result == -1) {
            // Not in Redis yet (never warmed, or Redis lost it): load from Postgres and try again.
            loadFromDatabase(message.productId());
            result = runDecreaseAndEnqueueScript(message, json);
        }
        return result == 1;
    }

    // "QUEUED" or "FAILED" while the order is not in the database, if this user placed it.
    // Empty when there is no status, it expired, or it belongs to someone else (callers then
    // answer 404, so other users can't tell the order exists).
    public Optional<String> findQueueStatus(UUID orderNo, Long userId) {
        Map<Object, Object> entry = redis.opsForHash().entries(statusKey(orderNo));
        if (entry.isEmpty() || !String.valueOf(userId).equals(entry.get("userId"))) {
            return Optional.empty();
        }
        return Optional.ofNullable((String) entry.get("status"));
    }

    // For the queue consumer: an order failed for good, so give its stock back, remove its
    // message from `listKey` and mark it FAILED atomically (see the script for why it must be one step).
    public void restoreAndRemove(OrderMessage message, String listKey, String json) {
        redis.execute(RESTORE_AND_REMOVE_SCRIPT,
                List.of(key(message.productId()), listKey, statusKey(message.orderNo())),
                String.valueOf(message.quantity()), json);
    }

    public void set(Long productId, int stock) {
        redis.opsForValue().set(key(productId), String.valueOf(stock));
    }

    public void delete(Long productId) {
        redis.delete(key(productId));
    }

    private long runDecreaseAndEnqueueScript(OrderMessage message, String json) {
        return redis.execute(DECREASE_AND_ENQUEUE_SCRIPT,
                List.of(key(message.productId()), QUEUE_KEY, statusKey(message.orderNo())),
                String.valueOf(message.quantity()), json,
                String.valueOf(message.userId()), String.valueOf(STATUS_TTL.toSeconds()));
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

    public static String statusKey(UUID orderNo) {
        return "order:status:" + orderNo;
    }
}
