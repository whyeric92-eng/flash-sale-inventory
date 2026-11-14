package com.flashsale.inventory.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.flashsale.inventory.dto.OrderMessage;
import com.flashsale.inventory.exception.ConflictException;
import com.flashsale.inventory.exception.NotFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// Background worker that turns queued order messages into database rows.
// SmartLifecycle lets Spring start it after the app is ready and stop it on shutdown.
@Component
public class OrderQueueConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueConsumer.class);

    // Messages being worked on. If the app dies mid-order, the message is still here (see 6.3b).
    public static final String PROCESSING_KEY = "order:processing";

    // Messages that can never be processed (unreadable JSON). Kept for a person to look at.
    public static final String DEAD_KEY = "order:dead";

    // Must stay below spring.data.redis.timeout (500ms), or the client may give up on the
    // blocking call before Redis answers.
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    // How long to wait after a temporary failure (database or Redis down) before trying again.
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final StringRedisTemplate redis;
    private final OrderService orderService;
    private final RedisStockService redisStockService;
    private final JsonMapper jsonMapper;
    private final boolean autoStartup;

    // volatile: stop() runs on a Spring thread, the loop runs on the worker thread.
    // Without it the worker may never see the change and keep looping.
    private volatile boolean running;
    private Thread worker;

    public OrderQueueConsumer(StringRedisTemplate redis, OrderService orderService,
            RedisStockService redisStockService, JsonMapper jsonMapper,
            @Value("${order-queue.consumer.auto-startup:true}") boolean autoStartup) {
        this.redis = redis;
        this.orderService = orderService;
        this.redisStockService = redisStockService;
        this.jsonMapper = jsonMapper;
        this.autoStartup = autoStartup;
    }

    @Override
    public void start() {
        running = true;
        while (redis.opsForList().move(PROCESSING_KEY, Direction.LEFT, 
            RedisStockService.QUEUE_KEY, Direction.RIGHT) != null ) {
                log.info("Requeued an order message left over from the last run");
            }
        worker = new Thread(this::loop, "order-queue-consumer");
        worker.start();
        log.info("Order queue consumer started");
    }

    @Override
    public void stop() {
        running = false;
        try {
            // The loop checks `running` at least every POLL_TIMEOUT, so this returns quickly once the current message is done.
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Order queue consumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // false in tests (src/test/resources/config/application.properties), so the consumer
    // doesn't grab messages that other tests put on the queue. Its own test calls start()/stop().
    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    private void loop() {
        while (running) {
            // Last line of defence: nothing thrown in here may end the thread, or the queue
            // would silently stop being worked on.
            try {
                String json = redis.opsForList().move(
                        RedisStockService.QUEUE_KEY, Direction.RIGHT,
                        PROCESSING_KEY, Direction.LEFT,
                        POLL_TIMEOUT);

                if (json == null) {
                    continue;
                }

                handle(json);
            } catch (RuntimeException e) {
                // Usually Redis being unreachable. Whatever was moved is still in processing,
                // so nothing is lost; startup recovery (6.3b-2) picks it up.
                log.error("Order queue loop failed, retrying in {}", RETRY_DELAY, e);
                pause(RETRY_DELAY);
            }
        }
    }

    // One message from start to finish. Every way out must leave the message somewhere
    // sensible: written and removed, given up (stock back), parked in the dead list, or requeued.
    private void handle(String json) {

        OrderMessage message;
        try {
            message = jsonMapper.readValue(json, OrderMessage.class);
        } catch (JacksonException e) {
            deadLetter(json, e);
            return;
        }

        if (message.orderNo() == null || message.userId() == null 
                || message.productId() == null || message.quantity() == null) {
            deadLetter(json, new IllegalArgumentException("Order message has missing fields"));
            return;
        }

        try {
            orderService.createOrderFromMessage(message);
            redis.opsForList().remove(PROCESSING_KEY, 1, json);
        } catch (ConflictException | NotFoundException | DataIntegrityViolationException e) {
            log.warn("Order {} failed for good, stock given back", message.orderNo(), e);
            redisStockService.restoreAndRemove(message.productId(), message.quantity(), PROCESSING_KEY, json);
        } catch (RuntimeException e) {
            retryLater(json, e);
        }
    }

    // Unreadable message: move it to the dead list so it stops blocking the queue.
    // Push first, then remove: a crash in between leaves a copy in both lists (harmless),
    // never in neither.
    private void deadLetter(String json, Exception cause) {
        log.error("Unreadable order message moved to {}: {}", DEAD_KEY, json, cause);
        redis.opsForList().leftPush(DEAD_KEY, json);
        redis.opsForList().remove(PROCESSING_KEY, 1, json);
    }

    // Temporary failure: put the message back at the front of the queue (the right end, where
    // BLMOVE takes from) and wait a little. Same order as above: a crash in between can only
    // lead to the message being processed twice, which 6.3b-2 makes harmless, never to losing it.
    private void retryLater(String json, Exception cause) {
        log.warn("Order message failed, will retry in {}: {}", RETRY_DELAY, json, cause);
        redis.opsForList().rightPush(RedisStockService.QUEUE_KEY, json);
        redis.opsForList().remove(PROCESSING_KEY, 1, json);
        pause(RETRY_DELAY);
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
