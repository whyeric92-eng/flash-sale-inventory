package com.flashsale.inventory.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashsale.inventory.entity.Order;
import com.flashsale.inventory.entity.OrderStatus;
import com.flashsale.inventory.entity.Product;
import com.flashsale.inventory.exception.ConflictException;
import com.flashsale.inventory.exception.NotFoundException;
import com.flashsale.inventory.repository.OrderRepository;
import com.flashsale.inventory.repository.UserRepository;

// No class-level @Transactional: createOrder must run with no transaction scope at all (see below).
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

    // Deliberately not @Transactional (not even NOT_SUPPORTED, which holds a DB connection for
    // the whole method and deadlocked the pool). The transaction is only the execute() block below.
    public Order createOrder(Long userId, Long productId, Integer quantity) {
        if (!redisStockService.tryDecrease(productId, quantity)) {
            throw new ConflictException("Insufficient stock");
        }
        try {
            return transactionTemplate.execute(status -> {
                // The database check stays as the final guard in case Redis is out of sync.
                inventoryService.decreaseStock(productId, quantity);

                Product product = productService.getProduct(productId);

                Order order = new Order();
                // Reference only: the id comes from a verified token, and the FK rejects unknown users.
                order.setUser(userRepository.getReferenceById(userId));
                order.setProduct(product);
                order.setQuantity(quantity);
                order.setStatus(OrderStatus.PENDING);
                order.setOrderNo(UUID.randomUUID());

                return orderRepository.save(order);
            });
        } catch (RuntimeException e) {
            // Redis is not part of the database transaction, so undo its decrease by hand.
            redisStockService.restore(productId, quantity);
            throw e;
        }
    }
}
