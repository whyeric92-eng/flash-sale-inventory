package com.flashsale.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flashsale.inventory.dto.ProductRequest;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.repository.ProductRepository;

import tools.jackson.databind.json.JsonMapper;

// Plain unit test, no Spring and no Docker: Redis and the database are Mockito fakes,
// so each test can make exactly one Redis call fail.
@ExtendWith(MockitoExtension.class)
class ProductCacheFallbackTest {

    private static final Long ID = 1L;
    private static final String KEY = "product:1";

    @Mock ProductRepository productRepository;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock TaskScheduler taskScheduler;

    ProductService productService;
    Product product;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, redis, JsonMapper.builder().build(), taskScheduler);
        product = new Product();
        product.setProductName("From database");
    }

    @Test
    void readFailureFallsBackToDatabase() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenThrow(new RedisConnectionFailureException("Redis is down"));
        when(productRepository.findById(ID)).thenReturn(Optional.of(product));

        assertThat(productService.getProductCached(ID).productName()).isEqualTo("From database");
    }

    @Test
    void corruptJsonFallsBackToDatabase() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn("not json");
        when(productRepository.findById(ID)).thenReturn(Optional.of(product));

        assertThat(productService.getProductCached(ID).productName()).isEqualTo("From database");
    }

    @Test
    void writeFailureStillReturnsProduct() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(null);
        when(productRepository.findById(ID)).thenReturn(Optional.of(product));
        doThrow(new RedisConnectionFailureException("Redis is down"))
                .when(valueOps).set(eq(KEY), anyString(), any(Duration.class));

        assertThat(productService.getProductCached(ID).productName()).isEqualTo("From database");
    }

    @Test
    void deleteFailureAfterCommitIsSwallowed() {
        when(productRepository.findById(ID)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);
        when(redis.delete(KEY)).thenThrow(new RedisConnectionFailureException("Redis is down"));

        // No real transaction here: turn on the callback list by hand, then play the
        // part of Spring and call afterCommit ourselves.
        TransactionSynchronizationManager.initSynchronization();
        try {
            productService.updateProduct(ID, new ProductRequest("New name"));

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                // The database is already committed; a Redis error must not turn that into a 500.
                assertThatCode(sync::afterCommit).as("first delete").doesNotThrowAnyException();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // The delayed second delete runs on the scheduler's thread; it must not throw either.
        ArgumentCaptor<Runnable> secondDelete = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(secondDelete.capture(), any(Instant.class));
        assertThatCode(secondDelete.getValue()::run).as("second delete").doesNotThrowAnyException();
    }
}
