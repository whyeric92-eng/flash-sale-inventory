package com.flashsale.inventory.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flashsale.inventory.dto.ProductRequest;
import com.flashsale.inventory.dto.ProductResponse;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.ProductRepository;

import tools.jackson.databind.json.JsonMapper;

// No class-level @Transactional: a transaction borrows a database connection as soon as
// it starts, so getProductCached would hold one even when Redis answers the request.
@Service
public class ProductService {

    private static final String KEY_PREFIX = "product:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    // Random extra TTL so keys written together don't all expire together (cache avalanche).
    private static final int CACHE_TTL_JITTER_SECONDS = 300;
    // "Not in the database" is cached briefly, so a newly created product shows up soon.
    private static final Duration MISSING_TTL = Duration.ofSeconds(30);
    private static final String MISSING_MARKER = "";

    private final ProductRepository productRepository;
    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public ProductService(ProductRepository productRepository, StringRedisTemplate redis, JsonMapper jsonMapper) {
        this.productRepository = productRepository;
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    // Returns the JPA entity, for other services that link to it (orders, inventory).
    // Not cached: an object rebuilt from Redis isn't managed by JPA.
    @Transactional(readOnly = true)
    public Product getProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product " + id + " not found"));
    }

    // Cache-aside read for GET /products/{id}. No transaction on purpose (see class comment).
    public ProductResponse getProductCached(Long id) {
        String key = KEY_PREFIX + id;
        String json = redis.opsForValue().get(key);

        if (json != null) {
            if (json.equals(MISSING_MARKER)) {
                throw new NotFoundException("Product " + id + " not found");
            }
            return jsonMapper.readValue(json, ProductResponse.class);
        } 

        Optional<Product> product = productRepository.findById(id);
        if (product.isEmpty()) {
            redis.opsForValue().set(key, MISSING_MARKER, MISSING_TTL);
            throw new NotFoundException("Product " + id + " not found");
        }

        ProductResponse dto = ProductResponse.from(product.get());
        String value = jsonMapper.writeValueAsString(dto);
        Duration ttl = CACHE_TTL.plusSeconds(ThreadLocalRandom.current().nextInt(CACHE_TTL_JITTER_SECONDS));
        redis.opsForValue().set(key, value, ttl);

        return dto;
    }

    @Transactional(readOnly = true)
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    @Transactional
    public Product createProduct(ProductRequest request) {
        Product product = new Product();
        product.setProductName(request.productName());
        return productRepository.save(product);
    }

    @Transactional
    public Product updateProduct(Long id, ProductRequest request) {
        Product product = getProduct(id);
        product.setProductName(request.productName());
        return productRepository.save(product);
    }

    @Transactional
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new NotFoundException("Product " + id + " not found");
        }
        productRepository.deleteById(id);
    }
}
