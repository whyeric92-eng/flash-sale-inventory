package com.flashsale.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.flashsale.inventory.TestcontainersConfiguration;
import com.flashsale.inventory.dto.OrderMessage;
import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.repository.InventoryRepository;
import com.flashsale.inventory.repository.ProductRepository;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisStockServiceTest {

    private static final String QUEUE = RedisStockService.QUEUE_KEY;

    @Autowired RedisStockService redisStockService;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired JsonMapper jsonMapper;

    // All tests share one Redis container, so start every test with an empty queue.
    @BeforeEach
    void clearQueue() {
        redis.delete(QUEUE);
    }

    @Test
    void enoughStockDecreasesAndQueuesMessage() {
        // Products 900_001+ never exist in the database; the stock key is set by hand,
        // so the script never needs to load from Postgres.
        Long productId = 900_001L;
        redisStockService.set(productId, 5);
        OrderMessage message = new OrderMessage(UUID.randomUUID(), 1L, productId, 2);

        assertThat(redisStockService.tryDecreaseAndEnqueue(message)).isTrue();

        assertThat(redis.opsForValue().get("stock:" + productId)).as("stock left").isEqualTo("3");
        List<String> queued = redis.opsForList().range(QUEUE, 0, -1);
        assertThat(queued).as("queued messages").hasSize(1);
        // Reading the JSON back must give the exact same message (records compare by fields).
        assertThat(jsonMapper.readValue(queued.get(0), OrderMessage.class)).isEqualTo(message);
    }

    @Test
    void notEnoughStockChangesNothing() {
        Long productId = 900_002L;
        redisStockService.set(productId, 1);
        OrderMessage message = new OrderMessage(UUID.randomUUID(), 1L, productId, 2);

        assertThat(redisStockService.tryDecreaseAndEnqueue(message)).isFalse();

        assertThat(redis.opsForValue().get("stock:" + productId)).as("stock left").isEqualTo("1");
        // The point of doing both in one script: a failed decrease must not leave a message behind.
        assertThat(redis.opsForList().size(QUEUE)).as("queue length").isZero();
    }

    @Test
    void missingStockKeyIsLoadedFromDatabase() {
        Product product = new Product();
        product.setProductName("Not warmed up");
        product = productRepository.save(product);
        Long productId = product.getId();

        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setStockQuantity(10);
        inventoryRepository.save(inventory);

        // A fresh product id, so Redis has no stock key for it: the first script run returns -1.
        assertThat(redis.hasKey("stock:" + productId)).isFalse();
        OrderMessage message = new OrderMessage(UUID.randomUUID(), 1L, productId, 1);

        assertThat(redisStockService.tryDecreaseAndEnqueue(message)).isTrue();

        assertThat(redis.opsForValue().get("stock:" + productId)).as("stock left").isEqualTo("9");
        // Queued exactly once, even though the script ran twice.
        assertThat(redis.opsForList().size(QUEUE)).as("queue length").isEqualTo(1);
    }
}
