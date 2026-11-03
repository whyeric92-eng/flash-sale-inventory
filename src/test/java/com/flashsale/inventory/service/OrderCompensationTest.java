package com.flashsale.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.flashsale.inventory.repository.ProductRepository;
import com.flashsale.inventory.repository.UserRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderCompensationTest {

    @Autowired OrderService orderService;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired StringRedisTemplate redis;

    @Test
    void redisStockIsRestoredWhenTheDatabasePartFails() {
        // Tests share one database, so the username must differ from other tests.
        User user = new User();
        user.setUsername("compensation-buyer");
        user.setPasswordHash("not-used-in-this-test");
        Long userId = userRepository.save(user).getId();

        Product product = new Product();
        product.setProductName("Out-of-sync item");
        product = productRepository.save(product);
        Long productId = product.getId();

        // Make Redis and Postgres disagree: Redis says 5 left, the database says 0.
        // Redis lets the order through, then the database rejects it.
        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setStockQuantity(0);
        inventoryRepository.save(inventory);
        String key = "stock:" + productId;
        redis.opsForValue().set(key, "5");

        assertThatThrownBy(() -> orderService.createOrder(userId, productId, 1))
                .isInstanceOf(ConflictException.class);

        // Redis went 5 -> 4 before the database failed; the catch block must put it back.
        assertThat(redis.opsForValue().get(key)).as("stock in Redis after rollback").isEqualTo("5");
    }
}
