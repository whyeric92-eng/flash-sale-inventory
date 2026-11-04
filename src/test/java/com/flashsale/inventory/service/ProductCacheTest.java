package com.flashsale.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.flashsale.inventory.TestcontainersConfiguration;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.ProductRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductCacheTest {

    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;
    @Autowired StringRedisTemplate redis;

    @Test
    void secondReadComesFromCache() {
        Product product = new Product();
        product.setProductName("Cached item");
        product = productRepository.save(product);
        Long id = product.getId();
        String key = "product:" + id;

        productService.getProductCached(id);

        assertThat(redis.hasKey(key)).as("cache key after first read").isTrue();
        assertThat(redis.getExpire(key)).as("cache TTL in seconds").isPositive();

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
    }
}
