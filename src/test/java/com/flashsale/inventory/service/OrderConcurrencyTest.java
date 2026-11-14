package com.flashsale.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.flashsale.inventory.TestcontainersConfiguration;
import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.entity.User;
import com.flashsale.inventory.exception.ConflictException;
import com.flashsale.inventory.repository.InventoryRepository;
import com.flashsale.inventory.repository.OrderRepository;
import com.flashsale.inventory.repository.ProductRepository;
import com.flashsale.inventory.repository.UserRepository;

// No @Transactional here: each thread must run and commit in its own transaction,
// just like real concurrent requests.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderConcurrencyTest {

    @Autowired OrderService orderService;
    @Autowired OrderQueueConsumer consumer;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired OrderRepository orderRepository;
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
    void concurrentOrdersDoNotOversell() throws Exception {
        int stock = 10;
        int threads = 50;

        User user = new User();
        user.setUsername("buyer");
        user.setPasswordHash("not-used-in-this-test");
        Long userId = userRepository.save(user).getId();

        Product product = new Product();
        product.setProductName("Flash sale item");
        product = productRepository.save(product);
        Long productId = product.getId();

        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setStockQuantity(stock);
        inventoryRepository.save(inventory);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGun.await();
                try {
                    orderService.createOrder(userId, productId, 1);
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }

        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        // get() rethrows anything other than ConflictException, so unexpected errors fail the test.
        for (Future<Void> f : futures) {
            f.get();
        }

        // Phase 1, the requests: Redis decides who gets stock. Exactly `stock` win, the rest get 409.
        assertThat(success.get()).as("orders that succeeded").isEqualTo(stock);
        assertThat(conflict.get()).as("orders rejected for low stock").isEqualTo(threads - stock);
        // Redis started empty (inventory was saved directly), so all threads raced to load it.
        // A wrong load would overwrite decreases and leave this above 0.
        assertThat(redis.opsForValue().get("stock:" + productId)).as("stock left in Redis").isEqualTo("0");
        // Nothing has touched the database yet: the winners are only on the queue.
        assertThat(redis.opsForList().size(RedisStockService.QUEUE_KEY)).as("queued orders").isEqualTo(stock);
        assertThat(databaseStock(productId)).as("Postgres stock before the consumer runs").isEqualTo(stock);

        // Phase 2, the consumer: it writes every queued order, and the database ends up matching Redis.
        consumer.start();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> orderRepository.countByProductId(productId) == stock);

        assertThat(databaseStock(productId)).as("Postgres stock left").isZero();
    }

    private int databaseStock(Long productId) {
        return inventoryRepository.findByProductId(productId).orElseThrow().getStockQuantity();
    }
}
