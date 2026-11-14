package com.flashsale.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.flashsale.inventory.TestcontainersConfiguration;
import com.flashsale.inventory.dto.OrderStatusResponse;
import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.entity.User;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.InventoryRepository;
import com.flashsale.inventory.repository.ProductRepository;
import com.flashsale.inventory.repository.UserRepository;

// The full path a buyer sees: place an order, then poll its status until it settles.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderStatusTest {

    @Autowired OrderService orderService;
    @Autowired OrderQueueConsumer consumer;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void emptyQueues() {
        redis.delete(List.of(RedisStockService.QUEUE_KEY, OrderQueueConsumer.PROCESSING_KEY));
    }

    @AfterEach
    void stopConsumer() {
        if (consumer.isRunning()) {
            consumer.stop();
        }
    }

    @Test
    void queuedOrderBecomesCreated() {
        Long userId = saveUser();
        Long productId = saveProductWithStock(10);

        UUID orderNo = orderService.createOrder(userId, productId, 1);

        // Consumer not started yet, so the order can only be on the queue.
        OrderStatusResponse queued = orderService.getOrderStatus(orderNo, userId);
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(queued.order()).isNull();

        consumer.start();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> orderService.getOrderStatus(orderNo, userId).status().equals("CREATED"));

        OrderStatusResponse created = orderService.getOrderStatus(orderNo, userId);
        assertThat(created.order()).isNotNull();
        assertThat(created.order().orderNo()).isEqualTo(orderNo);
        assertThat(created.order().quantity()).isEqualTo(1);
    }

    @Test
    void orderThatFailsInTheConsumerBecomesFailed() {
        Long userId = saveUser();
        // Redis says 5, the database says 0: the request is accepted, the consumer then fails it.
        Long productId = saveProductWithStock(0);
        redis.opsForValue().set("stock:" + productId, "5");

        UUID orderNo = orderService.createOrder(userId, productId, 1);
        consumer.start();

        await().atMost(Duration.ofSeconds(5))
                .until(() -> orderService.getOrderStatus(orderNo, userId).status().equals("FAILED"));
        assertThat(orderService.getOrderStatus(orderNo, userId).order()).isNull();
        assertThat(redis.opsForValue().get("stock:" + productId)).as("Redis stock given back").isEqualTo("5");
    }

    @Test
    void anotherUsersOrderIsNotFound() {
        Long buyerId = saveUser();
        Long otherUserId = saveUser();
        Long productId = saveProductWithStock(10);

        UUID orderNo = orderService.createOrder(buyerId, productId, 1);

        // While queued (answered from Redis)...
        assertThatThrownBy(() -> orderService.getOrderStatus(orderNo, otherUserId))
                .isInstanceOf(NotFoundException.class);

        // ...and once written (answered from the database).
        consumer.start();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> orderService.getOrderStatus(orderNo, buyerId).status().equals("CREATED"));
        assertThatThrownBy(() -> orderService.getOrderStatus(orderNo, otherUserId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void unknownOrderNumberIsNotFound() {
        Long userId = saveUser();

        assertThatThrownBy(() -> orderService.getOrderStatus(UUID.randomUUID(), userId))
                .isInstanceOf(NotFoundException.class);
    }

    private Long saveUser() {
        User user = new User();
        // Unique name: tests share one database, and usernames are UNIQUE.
        user.setUsername("status-buyer-" + UUID.randomUUID());
        user.setPasswordHash("not-used-in-this-test");
        return userRepository.save(user).getId();
    }

    private Long saveProductWithStock(int stock) {
        Product product = new Product();
        product.setProductName("Status item");
        product = productRepository.save(product);

        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setStockQuantity(stock);
        inventoryRepository.save(inventory);
        return product.getId();
    }
}
