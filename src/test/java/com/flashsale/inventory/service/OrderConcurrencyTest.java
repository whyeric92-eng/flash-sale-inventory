package com.flashsale.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
@Testcontainers
class OrderConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4-alpine");

    @Autowired OrderService orderService;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired OrderRepository orderRepository;

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

        int stockLeft = inventoryRepository.findByProductId(productId).orElseThrow().getStockQuantity();
        assertThat(success.get()).as("orders that succeeded").isEqualTo(stock);
        assertThat(conflict.get()).as("orders rejected for low stock").isEqualTo(threads - stock);
        assertThat(stockLeft).as("stock left").isZero();
        assertThat(orderRepository.count()).as("orders saved").isEqualTo(stock);
    }
}
