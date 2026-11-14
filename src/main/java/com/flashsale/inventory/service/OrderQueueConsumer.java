package com.flashsale.inventory.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.flashsale.inventory.dto.OrderMessage;

import tools.jackson.databind.json.JsonMapper;

// Background worker that turns queued order messages into database rows.
// SmartLifecycle lets Spring start it after the app is ready and stop it on shutdown.
@Component
public class OrderQueueConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueConsumer.class);

    // Messages being worked on. If the app dies mid-order, the message is still here (see 6.3b).
    public static final String PROCESSING_KEY = "order:processing";

    // Must stay below spring.data.redis.timeout (500ms), or the client may give up on the
    // blocking call before Redis answers.
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    private final StringRedisTemplate redis;
    private final OrderService orderService;
    private final JsonMapper jsonMapper;
    private final boolean autoStartup;

    // volatile: stop() runs on a Spring thread, the loop runs on the worker thread.
    // Without it the worker may never see the change and keep looping.
    private volatile boolean running;
    private Thread worker;

    public OrderQueueConsumer(StringRedisTemplate redis, OrderService orderService, JsonMapper jsonMapper,
            @Value("${order-queue.consumer.auto-startup:true}") boolean autoStartup) {
        this.redis = redis;
        this.orderService = orderService;
        this.jsonMapper = jsonMapper;
        this.autoStartup = autoStartup;
    }

    @Override
    public void start() {
        running = true;
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
            String json = redis.opsForList().move(
                RedisStockService.QUEUE_KEY, Direction.RIGHT,
                PROCESSING_KEY, Direction.LEFT,
                POLL_TIMEOUT);

            if (json == null) {
                continue;
            }

            OrderMessage message = jsonMapper.readValue(json, OrderMessage.class);

            orderService.createOrderFromMessage(message);

            redis.opsForList().remove(PROCESSING_KEY, 1, json);
        }
    }
}
