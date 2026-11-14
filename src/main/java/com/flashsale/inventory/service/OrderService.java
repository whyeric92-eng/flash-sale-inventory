package com.flashsale.inventory.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashsale.inventory.dto.OrderMessage;
import com.flashsale.inventory.dto.OrderResponse;
import com.flashsale.inventory.dto.OrderStatusResponse;
import com.flashsale.inventory.entity.Order;
import com.flashsale.inventory.entity.OrderStatus;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.exception.ConflictException;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.OrderRepository;
import com.flashsale.inventory.repository.UserRepository;

// No class-level @Transactional: each method sets up its own.
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final ProductService productService;
    private final RedisStockService redisStockService;
    private final TransactionTemplate transactionTemplate;

    public OrderService(OrderRepository orderRepository, UserRepository userRepository,
            InventoryService inventoryService, ProductService productService,
            RedisStockService redisStockService, TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.inventoryService = inventoryService;
        this.productService = productService;
        this.redisStockService = redisStockService;
        this.transactionTemplate = transactionTemplate;
    }

    // Another user's order gives 404, not 403, so callers can't tell it exists.
    @Transactional(readOnly = true)
    public Order getOrder(Long id, Long userId) {
        return orderRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Order " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Order> getOrdersForUser(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    // CREATED once the consumer has written it; QUEUED or FAILED while only Redis knows it.
    // Another user's order number gives 404, like getOrder.
    @Transactional(readOnly = true)
    public OrderStatusResponse getOrderStatus(UUID orderNo, Long userId) {
        Optional<Order> order = orderRepository.findByOrderNoAndUserId(orderNo, userId);
        if (order.isPresent()) {
            return new OrderStatusResponse(orderNo, "CREATED", OrderResponse.from(order.get()));
        }
        return redisStockService.findQueueStatus(orderNo, userId)
                .map(status -> new OrderStatusResponse(orderNo, status, null))
                .orElseThrow(() -> new NotFoundException("Order " + orderNo + " not found"));
    }

    // Redis only: take the stock and queue the order. The consumer writes it to the database.
    public UUID createOrder(Long userId, Long productId, Integer quantity) {
        UUID orderNo = UUID.randomUUID();

        OrderMessage message = new OrderMessage(orderNo, userId, productId, quantity);

        if (!redisStockService.tryDecreaseAndEnqueue(message)) {
            throw new ConflictException("Insufficient stock");
        }
        return orderNo;
    }

    public Order createOrderFromMessage(OrderMessage message) {
        return transactionTemplate.execute(status -> {
            Optional<Order> existing = orderRepository.findByOrderNo(message.orderNo());
            if (existing.isPresent()) {
                return existing.get();
            }
            // The database check stays as the final guard in case Redis is out of sync.
            inventoryService.decreaseStock(message.productId(), message.quantity());

            Product product = productService.getProduct(message.productId());

            Order order = new Order();
            order.setUser(userRepository.getReferenceById(message.userId()));
            order.setProduct(product);
            order.setQuantity(message.quantity());
            order.setStatus(OrderStatus.PENDING);
            order.setOrderNo(message.orderNo());

            return orderRepository.save(order);
        });
    }
}
