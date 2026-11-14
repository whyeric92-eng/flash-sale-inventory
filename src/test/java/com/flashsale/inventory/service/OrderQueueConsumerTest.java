package com.flashsale.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.flashsale.inventory.TestcontainersConfiguration;
import com.flashsale.inventory.dto.OrderMessage;
import com.flashsale.inventory.entity.Inventory;
import com.flashsale.inventory.entity.Order;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.entity.User;
import com.flashsale.inventory.repository.InventoryRepository;
import com.flashsale.inventory.repository.OrderRepository;
import com.flashsale.inventory.repository.ProductRepository;
import com.flashsale.inventory.repository.UserRepository;

// The consumer does not start on its own in tests (see src/test/resources/config/application.properties),
// so each test here starts it and stops it again.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderQueueConsumerTest {

    @Autowired OrderQueueConsumer consumer;
    @Autowired RedisStockService redisStockService;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void emptyQueues() {
        redis.delete(List.of(RedisStockService.QUEUE_KEY, OrderQueueConsumer.PROCESSING_KEY,
                OrderQueueConsumer.DEAD_KEY));
    }

    @AfterEach
    void stopConsumer() {
        if (consumer.isRunning()) {
            consumer.stop();
        }
    }

    @Test
    void queuedOrderEndsUpInDatabase() {
        Long userId = saveUser();
        Long productId = saveProductWithStock(10);
        OrderMessage message = new OrderMessage(UUID.randomUUID(), userId, productId, 2);

        // Same call the order endpoint will make in 6.4: Redis stock down, message on the queue.
        assertThat(redisStockService.tryDecreaseAndEnqueue(message)).isTrue();
        consumer.start();

        // Polls every 100ms by default and moves on as soon as the order shows up.
        await().atMost(Duration.ofSeconds(5))
                .until(() -> orderRepository.findByOrderNo(message.orderNo()).isPresent());

        Order order = orderRepository.findByOrderNo(message.orderNo()).orElseThrow();
        assertThat(order.getUser().getId()).isEqualTo(userId);
        assertThat(order.getProduct().getId()).isEqualTo(productId);
        assertThat(order.getQuantity()).isEqualTo(2);

        assertThat(databaseStock(productId)).as("Postgres stock").isEqualTo(8);
        assertThat(redis.opsForList().size(RedisStockService.QUEUE_KEY)).as("queue length").isZero();
        // LREM runs right after the insert, so give it a moment instead of checking once.
        await().atMost(Duration.ofSeconds(2))
                .until(() -> redis.opsForList().size(OrderQueueConsumer.PROCESSING_KEY) == 0);
    }

    @Test
    void severalQueuedOrdersAreAllWritten() {
        Long userId = saveUser();
        Long productId = saveProductWithStock(10);

        List<UUID> orderNos = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            OrderMessage message = new OrderMessage(UUID.randomUUID(), userId, productId, 1);
            assertThat(redisStockService.tryDecreaseAndEnqueue(message)).isTrue();
            orderNos.add(message.orderNo());
        }
        consumer.start();

        await().atMost(Duration.ofSeconds(5))
                .until(() -> orderNos.stream().allMatch(no -> orderRepository.findByOrderNo(no).isPresent()));

        assertThat(databaseStock(productId)).as("Postgres stock").isEqualTo(5);
        assertThat(redis.opsForValue().get("stock:" + productId)).as("Redis stock").isEqualTo("5");
    }

    @Test
    void failedOrderGivesRedisStockBack() {
        Long userId = saveUser();
        // Postgres has nothing left, but Redis still thinks there are 5: the "admin changed the
        // stock in the database only" case. Redis lets the order through, the database refuses it.
        Long productId = saveProductWithStock(0);
        redisStockService.set(productId, 5);
        OrderMessage message = new OrderMessage(UUID.randomUUID(), userId, productId, 1);

        assertThat(redisStockService.tryDecreaseAndEnqueue(message)).isTrue();
        assertThat(redisStock(productId)).as("Redis stock after queueing").isEqualTo("4");
        consumer.start();

        // The one Redis took for this order comes back.
        await().atMost(Duration.ofSeconds(5)).until(() -> "5".equals(redisStock(productId)));

        assertThat(orderRepository.findByOrderNo(message.orderNo())).as("order row").isEmpty();
        assertThat(databaseStock(productId)).as("Postgres stock").isZero();
        assertThat(redis.opsForList().size(OrderQueueConsumer.PROCESSING_KEY)).as("processing length").isZero();
    }

    @Test
    void unreadableMessagesGoToDeadListAndConsumerKeepsGoing() {
        Long userId = saveUser();
        Long productId = saveProductWithStock(10);

        // LPUSH adds on the left and the consumer takes from the right, so these two come out
        // first, and the good order after them.
        redis.opsForList().leftPush(RedisStockService.QUEUE_KEY, "not json");
        redis.opsForList().leftPush(RedisStockService.QUEUE_KEY, "{}");
        OrderMessage good = new OrderMessage(UUID.randomUUID(), userId, productId, 1);
        assertThat(redisStockService.tryDecreaseAndEnqueue(good)).isTrue();
        consumer.start();

        // The good order still gets written: the bad ones did not kill the thread or block the queue.
        await().atMost(Duration.ofSeconds(5))
                .until(() -> orderRepository.findByOrderNo(good.orderNo()).isPresent());

        assertThat(redis.opsForList().range(OrderQueueConsumer.DEAD_KEY, 0, -1))
                .as("dead list").containsExactlyInAnyOrder("not json", "{}");
        await().atMost(Duration.ofSeconds(2))
                .until(() -> redis.opsForList().size(OrderQueueConsumer.PROCESSING_KEY) == 0);
    }

    @Test
    void sameMessageTwiceMakesOneOrder() {
        Long userId = saveUser();
        Long productId = saveProductWithStock(10);
        OrderMessage message = new OrderMessage(UUID.randomUUID(), userId, productId, 1);
        assertThat(redisStockService.tryDecreaseAndEnqueue(message)).isTrue();

        // Push the exact same JSON again, as if it had been requeued after a crash.
        String json = redis.opsForList().index(RedisStockService.QUEUE_KEY, 0);
        redis.opsForList().leftPush(RedisStockService.QUEUE_KEY, json);
        consumer.start();

        // Wait until both copies have been taken and dealt with.
        await().atMost(Duration.ofSeconds(5)).until(() ->
                redis.opsForList().size(RedisStockService.QUEUE_KEY) == 0
                        && redis.opsForList().size(OrderQueueConsumer.PROCESSING_KEY) == 0);

        assertThat(orderRepository.findByOrderNo(message.orderNo())).as("order row").isPresent();
        // Taken once from each store. Without the "already exists?" check, the second copy would
        // hit the UNIQUE constraint, count as a business failure and give the Redis stock back (10).
        assertThat(databaseStock(productId)).as("Postgres stock").isEqualTo(9);
        assertThat(redisStock(productId)).as("Redis stock").isEqualTo("9");
    }

    @Test
    void messageLeftInProcessingIsPickedUpOnStart() {
        Long userId = saveUser();
        Long productId = saveProductWithStock(10);
        OrderMessage message = new OrderMessage(UUID.randomUUID(), userId, productId, 1);
        assertThat(redisStockService.tryDecreaseAndEnqueue(message)).isTrue();

        // Do what BLMOVE does, then "crash": the message sits in processing with no worker on it.
        redis.opsForList().move(RedisStockService.QUEUE_KEY, Direction.RIGHT,
                OrderQueueConsumer.PROCESSING_KEY, Direction.LEFT);

        consumer.start();

        await().atMost(Duration.ofSeconds(5))
                .until(() -> orderRepository.findByOrderNo(message.orderNo()).isPresent());
        assertThat(databaseStock(productId)).as("Postgres stock").isEqualTo(9);
    }

    @Test
    void stopEndsTheWorkerThread() {
        consumer.start();
        assertThat(consumer.isRunning()).isTrue();

        consumer.stop();

        assertThat(consumer.isRunning()).isFalse();
        // The loop wakes up at least every 200ms to check `running`, so the thread is gone by now.
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(t -> t.getName().equals("order-queue-consumer") && t.isAlive());
    }

    private Long saveUser() {
        User user = new User();
        // Unique name: tests share one database, and usernames are UNIQUE.
        user.setUsername("queue-buyer-" + UUID.randomUUID());
        user.setPasswordHash("not-used-in-this-test");
        return userRepository.save(user).getId();
    }

    private Long saveProductWithStock(int stock) {
        Product product = new Product();
        product.setProductName("Queued item");
        product = productRepository.save(product);

        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setStockQuantity(stock);
        inventoryRepository.save(inventory);
        return product.getId();
    }

    private String redisStock(Long productId) {
        return redis.opsForValue().get("stock:" + productId);
    }

    private int databaseStock(Long productId) {
        return inventoryRepository.findByProductId(productId).orElseThrow().getStockQuantity();
    }
}
