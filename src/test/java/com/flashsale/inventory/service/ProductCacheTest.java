package com.flashsale.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashsale.inventory.TestcontainersConfiguration;
import com.flashsale.inventory.dto.ProductRequest;
import com.flashsale.inventory.dto.ProductResponse;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.ProductRepository;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductCacheTest {

    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JsonMapper jsonMapper;

    @Test
    void secondReadComesFromCache() {
        Product product = new Product();
        product.setProductName("Cached item");
        product = productRepository.save(product);
        Long id = product.getId();
        String key = "product:" + id;

        productService.getProductCached(id);

        assertThat(redis.hasKey(key)).as("cache key after first read").isTrue();
        // 30 minutes plus up to 5 minutes of jitter; a little slack below for rounding.
        assertThat(redis.getExpire(key)).as("cache TTL in seconds").isBetween(30 * 60L - 5, 35 * 60L);

        // Change the row behind the service's back. If the next read still sees the
        // old name, it came from Redis, not from the database.
        product.setProductName("Changed in DB");
        productRepository.save(product);

        assertThat(productService.getProductCached(id).productName()).isEqualTo("Cached item");
    }

    @Test
    void missingProductIsCachedToo() {
        // No test creates this many products, so this id never exists.
        Long id = 999_999L;
        String key = "product:" + id;

        assertThatThrownBy(() -> productService.getProductCached(id))
                .isInstanceOf(NotFoundException.class);

        // An empty string marks "not in the database". A real entry is always JSON
        // starting with "{", so the two can't be mixed up.
        assertThat(redis.opsForValue().get(key)).as("null marker in Redis").isEqualTo("");
        assertThat(redis.getExpire(key)).as("null marker TTL in seconds").isBetween(1L, 30L);
    }

    // The tests below call @Transactional service methods from outside, so each call
    // commits for real and any afterCommit work runs before the call returns.

    @Test
    void updateEvictsCache() {
        Long id = saveProduct("Old name");
        String key = "product:" + id;
        productService.getProductCached(id);

        productService.updateProduct(id, new ProductRequest("New name"));

        assertThat(redis.hasKey(key)).as("cache key after update").isFalse();
        assertThat(productService.getProductCached(id).productName()).isEqualTo("New name");
    }

    @Test
    void deleteEvictsCache() {
        Long id = saveProduct("Soon gone");
        String key = "product:" + id;
        productService.getProductCached(id);

        productService.deleteProduct(id);

        assertThat(redis.hasKey(key)).as("cache key after delete").isFalse();
        assertThatThrownBy(() -> productService.getProductCached(id))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createClearsMissingMarker() {
        // Someone asks for the next id before it exists, so "" gets cached for it.
        Long nextId = saveProduct("Placeholder") + 1;
        assertThatThrownBy(() -> productService.getProductCached(nextId))
                .isInstanceOf(NotFoundException.class);

        Long id = productService.createProduct(new ProductRequest("Brand new")).getId();

        assertThat(id).as("ids are handed out in order").isEqualTo(nextId);
        assertThat(productService.getProductCached(id).productName()).isEqualTo("Brand new");
    }

    @Test
    void evictionWaitsForCommit() {
        Long id = saveProduct("Old name");
        String key = "product:" + id;

        transactionTemplate.executeWithoutResult(status -> {
            // Joins this transaction, so the new name is written but not committed yet.
            productService.updateProduct(id, new ProductRequest("New name"));

            // Another thread can't see uncommitted data: it reads the old name and
            // caches it. If the cache were cleared before commit, this would stick.
            String seen = CompletableFuture.supplyAsync(() -> productService.getProductCached(id))
                    .join()
                    .productName();
            assertThat(seen).as("reader before commit").isEqualTo("Old name");
        });

        // Clearing after commit removes what that reader cached.
        assertThat(redis.hasKey(key)).as("cache key after commit").isFalse();
        assertThat(productService.getProductCached(id).productName()).isEqualTo("New name");
    }

    @Test
    void secondDeleteClearsLateStaleWrite() throws InterruptedException {
        Long id = saveProduct("Old name");
        String key = "product:" + id;

        productService.updateProduct(id, new ProductRequest("New name"));

        // A slow reader read "Old name" before the commit and only writes it now,
        // after the first delete already ran.
        String stale = jsonMapper.writeValueAsString(new ProductResponse(id, "Old name"));
        redis.opsForValue().set(key, stale, Duration.ofMinutes(30));

        // Longer than the delay before the second delete.
        Thread.sleep(1000);

        assertThat(redis.hasKey(key)).as("stale cache key after the second delete").isFalse();
    }

    private Long saveProduct(String name) {
        Product product = new Product();
        product.setProductName(name);
        return productRepository.save(product).getId();
    }
}
